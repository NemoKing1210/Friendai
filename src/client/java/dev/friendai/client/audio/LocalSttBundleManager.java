package dev.friendai.client.audio;

import dev.friendai.features.speech.stt.LocalSttCatalog;
import dev.friendai.features.speech.stt.LocalSttCatalog.LocalModelFile;
import dev.friendai.features.speech.stt.LocalSttCatalog.LocalSttModel;
import dev.friendai.foundation.text.UserFacingException;
import dev.friendai.foundation.text.UserMessage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Installs one local recognition bundle from {@link LocalSttCatalog}.
 *
 * <p>Every file is downloaded into a private staging directory beside the install, checked against the size and
 * SHA-256 the catalog declares, and only then published. A manifest marker written after verification is what makes
 * a bundle "installed"; a caller that only checks file sizes cannot mistake a truncated or half-published bundle for
 * a complete one. An existing valid installation is verified and adopted without a download, so a legacy Whisper
 * file keeps working, and a failed retry leaves the previous files untouched.
 *
 * <p>The manager never loads a native library and knows nothing about configuration or UI. At most
 * {@link #MAX_CONCURRENT_INSTALLS} bundles download at once; concurrent callers for one bundle share a single
 * install, and a cancelled caller never cancels it for the others. Each request is time-limited, reads at most one
 * byte past the declared size, and accepts only a final HTTP 200; the shared client follows the model host's
 * redirect to its CDN, and the bytes are still checked against the catalog's size and hash.
 */
public final class LocalSttBundleManager {
    /** Directory name suffix of the staging copy, next to the install; both must share a filesystem to rename. */
    private static final String STAGING_SUFFIX = ".staging";

    /** Marker recording that this bundle's files were hashed against the catalog. */
    private static final String MARKER_PREFIX = ".friendai-bundle-";

    private static final String MARKER_TEMP_SUFFIX = ".tmp";

    /** Upper bounds that only catch a broken or hostile catalog entry; no real bundle comes close. */
    static final int MAX_FILES = 64;

    static final long MAX_FILE_BYTES = 4L * 1024 * 1024 * 1024;
    static final long MAX_BUNDLE_BYTES = 8L * 1024 * 1024 * 1024;

    /** Whole-process limit, so two large model downloads cannot saturate the connection at once. */
    public static final int MAX_CONCURRENT_INSTALLS = 2;

    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(30);

    private final Path gameDir;
    private final HttpClient http;
    private final Semaphore installSlots = new Semaphore(MAX_CONCURRENT_INSTALLS, true);
    /** One running install per model; a second caller joins it instead of deleting its staging directory. */
    private final Map<String, CompletableFuture<Path>> inFlight = new ConcurrentHashMap<>();
    /** Models whose files were hashed successfully in this process; re-hashing a 650 MB encoder is not free. */
    private final Set<String> verified = ConcurrentHashMap.newKeySet();

    public LocalSttBundleManager(Path gameDir, HttpClient http) {
        this.gameDir = gameDir;
        this.http = http;
    }

    /** Where the bundle lives once installed, resolved from the catalog's relative path. */
    public Path dir(LocalSttModel model) {
        return gameDir.resolve(model.bundleDir());
    }

    /** One manifest file of an installed bundle. */
    public Path file(LocalSttModel model, LocalModelFile file) {
        return dir(model).resolve(file.file());
    }

    /**
     * True when a marker matching this exact revision and manifest is present and every file has its declared size.
     * This is the cheap readiness check used while building a screen; it does not rehash the model.
     */
    public boolean isInstalled(LocalSttModel model) {
        try {
            validate(model);
        } catch (IllegalArgumentException brokenManifest) {
            return false;
        }
        if (!markerMatches(model)) {
            return false;
        }
        return hasFiles(model);
    }

    /** True when every manifest file exists with its declared size; says nothing about its content. */
    public boolean hasFiles(LocalSttModel model) {
        try {
            validate(model);
        } catch (IllegalArgumentException brokenManifest) {
            return false;
        }
        for (LocalModelFile file : model.files()) {
            Path path = file(model, file);
            try {
                if (!Files.isRegularFile(path) || Files.size(path) != file.size()) {
                    return false;
                }
            } catch (IOException unreadable) {
                return false;
            }
        }
        return true;
    }

    /**
     * Hashes every file and writes the marker when they all match. Called on a worker before a native model is
     * loaded, so a corrupt, truncated or substituted bundle is refused before the native library sees it. Repeated
     * calls in one process reuse the earlier result; a changed installation needs a restart or a reinstall.
     */
    public boolean verifyInstalled(LocalSttModel model) {
        validate(model);
        String key = model.id() + "@" + model.revision();
        if (verified.contains(key)) {
            return true;
        }
        if (!hasFiles(model)) {
            return false;
        }
        for (LocalModelFile file : model.files()) {
            if (!hashMatches(file(model, file), file)) {
                return false;
            }
        }
        writeMarker(model);
        verified.add(key);
        return true;
    }

    /** Installs a bundle without progress reporting. */
    public CompletableFuture<Path> install(LocalSttModel model) {
        return install(model, ProgressListener.none());
    }

    /**
     * Downloads, verifies and publishes one bundle. A valid existing installation is adopted without a request. A
     * second call for the same model joins the running install and completes with it; cancelling either caller's
     * future never cancels the shared install, and progress is not replayed for the late caller.
     *
     * @param progress called from the HTTP client's threads, possibly with a fraction that does not move when a
     *     retry re-downloads a file
     */
    public CompletableFuture<Path> install(LocalSttModel model, ProgressListener progress) {
        validate(model);
        CompletableFuture<Path> shared;
        synchronized (this) {
            shared = inFlight.get(model.id());
            if (shared == null || shared.isDone()) {
                CompletableFuture<Path> created = start(model, progress);
                inFlight.put(model.id(), created);
                created.whenComplete((ignored, error) -> inFlight.remove(model.id(), created));
                shared = created;
            }
        }
        // Each caller owns a plain relay future: cancelling it never cancels the shared install or another caller.
        CompletableFuture<Path> caller = new CompletableFuture<>();
        shared.whenComplete((result, error) -> {
            if (error != null) caller.completeExceptionally(error);
            else caller.complete(result);
        });
        return caller;
    }

    private CompletableFuture<Path> start(LocalSttModel model, ProgressListener progress) {
        Path staging = staging(model), bundle = dir(model);
        AtomicLong written = new AtomicLong();
        long total = model.totalBytes();
        AtomicBoolean slotHeld = new AtomicBoolean();
        return CompletableFuture.supplyAsync(() -> {
                    installSlots.acquireUninterruptibly();
                    slotHeld.set(true);
                    // A valid installation (including one made before the marker existed) is adopted, not redownloaded.
                    if (verifyInstalled(model)) {
                        return true;
                    }
                    // A crash leaves a staging directory behind; it is never trusted, so it is dropped first.
                    try {
                        deleteRecursively(staging);
                        Files.createDirectories(staging);
                    } catch (IOException e) {
                        throw network();
                    }
                    return false;
                })
                .thenCompose(alreadyInstalled -> alreadyInstalled
                        ? CompletableFuture.completedFuture(bundle)
                        : download(model, staging, 0, written, total, progress)
                                .thenApply(ignored -> publish(model, staging, bundle)))
                .handle((result, error) -> {
                    if (slotHeld.get()) {
                        installSlots.release();
                    }
                    if (error == null) {
                        return result;
                    }
                    cleanup(staging);
                    Throwable cause =
                            error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
                    if (cause instanceof CancellationException cancelled) {
                        throw cancelled;
                    }
                    if (cause instanceof RuntimeException runtime) {
                        throw runtime;
                    }
                    throw new CompletionException(cause);
                });
    }

    /** Downloads the manifest files one after another, so a bundle never runs several connections at once. */
    private CompletableFuture<Void> download(
            LocalSttModel model, Path staging, int index, AtomicLong written, long total, ProgressListener progress) {
        List<LocalModelFile> files = model.files();
        if (index >= files.size()) {
            return CompletableFuture.completedFuture(null);
        }
        LocalModelFile file = files.get(index);
        progress.fileStarted(file);
        HttpRequest request = HttpRequest.newBuilder(url(model, file))
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
                .thenApply(response -> {
                    if (response.statusCode() != 200) {
                        closeQuietly(response.body());
                        throw network();
                    }
                    save(
                            file,
                            response.body(),
                            staging.resolve(file.file() + ".part"),
                            staging.resolve(file.file()),
                            written,
                            total,
                            progress);
                    return null;
                })
                .thenCompose(ignored -> download(model, staging, index + 1, written, total, progress));
    }

    /**
     * Writes one body to its part file, hashing as it goes. One byte past the declared size is enough to reject a
     * longer body without reading all of it.
     */
    private static void save(
            LocalModelFile file,
            InputStream body,
            Path part,
            Path target,
            AtomicLong written,
            long total,
            ProgressListener progress) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        long received = 0;
        try (InputStream in = body) {
            try (OutputStream out =
                    Files.newOutputStream(part, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                byte[] buffer = new byte[64 * 1024];
                long limit = file.size();
                while (received <= limit) {
                    int allowed = (int) Math.min(buffer.length, limit + 1 - received);
                    int n = in.read(buffer, 0, allowed);
                    if (n < 0) {
                        break;
                    }
                    received += n;
                    if (received > limit) {
                        break;
                    }
                    out.write(buffer, 0, n);
                    digest.update(buffer, 0, n);
                    progress.bytesWritten(written.addAndGet(n), total);
                }
            }
            if (received != file.size()
                    || !HexFormat.of().formatHex(digest.digest()).equalsIgnoreCase(file.sha256())) {
                throw corrupt();
            }
            move(part, target);
        } catch (UserFacingException e) {
            throw e;
        } catch (IOException e) {
            throw network();
        }
    }

    /**
     * Makes the verified staging files visible and writes the marker. A directory that does not exist yet is renamed
     * in one step, so the bundle appears complete or not at all. An existing directory is the shared legacy model
     * folder or a damaged previous install; its entries are replaced one at a time, each only after its own verified
     * rename. Until the marker lands the bundle is not "installed" even if every file is already in place.
     */
    private Path publish(LocalSttModel model, Path staging, Path bundle) {
        try {
            if (!Files.exists(bundle)) {
                Files.createDirectories(bundle.getParent());
                move(staging, bundle);
            } else {
                try (Stream<Path> files = Files.list(staging)) {
                    for (Path file : files.toList()) {
                        Files.move(
                                file,
                                bundle.resolve(file.getFileName().toString()),
                                StandardCopyOption.ATOMIC_MOVE,
                                StandardCopyOption.REPLACE_EXISTING);
                    }
                }
                deleteRecursively(staging);
            }
            writeMarker(model);
            verified.add(model.id() + "@" + model.revision());
            return bundle;
        } catch (IOException e) {
            throw network();
        }
    }

    /** The private directory the bundle is assembled in, next to its final location so a rename stays atomic. */
    private Path staging(LocalSttModel model) {
        return dir(model).getParent().resolve(model.id() + STAGING_SUFFIX);
    }

    private Path marker(LocalSttModel model) {
        return dir(model).resolve(MARKER_PREFIX + model.id());
    }

    private URI url(LocalSttModel model, LocalModelFile file) {
        return URI.create(model.downloadBaseUrl() + "/" + file.file());
    }

    private boolean markerMatches(LocalSttModel model) {
        Path marker = marker(model);
        try {
            if (!Files.isRegularFile(marker)) {
                return false;
            }
            String id = null, revision = null, manifest = null;
            for (String line : Files.readAllLines(marker, StandardCharsets.UTF_8)) {
                int equals = line.indexOf('=');
                if (equals <= 0) {
                    continue;
                }
                String key = line.substring(0, equals);
                String value = line.substring(equals + 1);
                switch (key) {
                    case "id" -> id = value;
                    case "revision" -> revision = value;
                    case "manifest" -> manifest = value;
                    default -> {
                        // A future marker may carry extra fields; they do not affect this revision.
                    }
                }
            }
            return model.id().equals(id)
                    && model.revision().equals(revision)
                    && manifestDigest(model).equals(manifest);
        } catch (IOException | RuntimeException unreadable) {
            return false;
        }
    }

    private void writeMarker(LocalSttModel model) {
        Path marker = marker(model);
        Path temp = marker.resolveSibling(marker.getFileName() + MARKER_TEMP_SUFFIX);
        String content =
                "id=" + model.id() + "\nrevision=" + model.revision() + "\nmanifest=" + manifestDigest(model) + "\n";
        try {
            Files.createDirectories(marker.getParent());
            Files.writeString(
                    temp,
                    content,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            move(temp, marker);
        } catch (IOException e) {
            throw network();
        }
    }

    private static String manifestDigest(LocalSttModel model) {
        MessageDigest digest = digest();
        digest.update((model.revision() + "\n").getBytes(StandardCharsets.UTF_8));
        for (LocalModelFile file : model.files()) {
            digest.update(
                    (file.file() + "\0" + file.size() + "\0" + file.sha256() + "\n").getBytes(StandardCharsets.UTF_8));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static boolean hashMatches(Path path, LocalModelFile file) {
        MessageDigest digest = digest();
        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = Files.newInputStream(path)) {
            int n;
            while ((n = in.read(buffer)) >= 0) {
                digest.update(buffer, 0, n);
            }
        } catch (IOException unreadable) {
            return false;
        }
        return HexFormat.of().formatHex(digest.digest()).equalsIgnoreCase(file.sha256());
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** Rejects a manifest that cannot describe a bundle, so a bad catalog entry fails before any request. */
    private static void validate(LocalSttModel model) {
        LocalSttCatalog.validate(model);
        List<LocalModelFile> files = model.files();
        if (files.size() > MAX_FILES) {
            throw new IllegalArgumentException("bundle has too many files: " + files.size());
        }
        long total = 0;
        for (LocalModelFile file : files) {
            if (file.size() > MAX_FILE_BYTES) {
                throw new IllegalArgumentException("bundle file size out of range: " + file);
            }
            total += file.size();
            if (total > MAX_BUNDLE_BYTES) {
                throw new IllegalArgumentException("bundle is larger than the cap: " + model.id());
            }
        }
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void cleanup(Path staging) {
        try {
            deleteRecursively(staging);
        } catch (IOException ignored) {
            // best effort: the next install deletes the staging directory before downloading anyway
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            for (Path entry : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(entry);
            }
        }
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException ignored) {
            // nothing to do
        }
    }

    private static UserFacingException corrupt() {
        return new UserFacingException(UserMessage.of("friendai.voice.error.model_corrupt"));
    }

    private static UserFacingException network() {
        return new UserFacingException(UserMessage.of("friendai.error.llm.network"));
    }

    /** Called from the HTTP client's threads while an install runs; an implementation must be thread-safe. */
    public interface ProgressListener {
        /** Called once per manifest file, in manifest order, before its first byte is requested. */
        void fileStarted(LocalModelFile file);

        /** Bytes verified and written for this bundle so far, out of {@link LocalSttModel#totalBytes()}. */
        void bytesWritten(long written, long total);

        static ProgressListener none() {
            return new ProgressListener() {
                @Override
                public void fileStarted(LocalModelFile file) {}

                @Override
                public void bytesWritten(long written, long total) {}
            };
        }
    }
}
