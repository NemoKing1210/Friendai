package dev.friendai.client.audio;

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
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Installs one local recognition bundle from {@link dev.friendai.features.speech.stt.LocalSttCatalog}.
 *
 * <p>Every file is downloaded into a private staging directory beside the install, checked against the size and
 * SHA-256 the catalog declares, and only then published; a half-written bundle is never visible to a caller that
 * checks {@link #isInstalled(LocalSttModel)}. A failed attempt deletes its staging directory and leaves an
 * existing installation untouched, so a retry starts clean and the old bundle keeps working meanwhile.
 *
 * <p>The manager never loads a native library and knows nothing about configuration or UI: it answers "is this
 * bundle complete on disk?" and "install it from this base URL". Files are fetched one after another, each request
 * is time-limited and reads at most one byte past the declared size, and redirects are not followed, so any status
 * other than 200 fails the install instead of pulling bytes from another host.
 */
public final class LocalSttBundleManager {
    /** Directory name suffix of the staging copy, next to the install; both must share a filesystem to rename. */
    private static final String STAGING_SUFFIX = ".staging";

    /** Upper bounds that only catch a broken or hostile catalog entry; no real bundle comes close. */
    static final int MAX_FILES = 64;

    static final long MAX_FILE_BYTES = 4L * 1024 * 1024 * 1024;
    static final long MAX_BUNDLE_BYTES = 8L * 1024 * 1024 * 1024;

    /** A file name inside the bundle: one plain segment, so it can neither escape the directory nor be a URL. */
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    private static final Pattern SHA256 = Pattern.compile("[0-9a-fA-F]{64}");

    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(30);

    private final Path gameDir;
    private final HttpClient http;
    private final String baseUrl;
    /** One running install per model; a second caller joins it instead of deleting its staging directory. */
    private final Map<String, CompletableFuture<Path>> inFlight = new ConcurrentHashMap<>();

    public LocalSttBundleManager(Path gameDir, HttpClient http, String baseUrl) {
        this.gameDir = gameDir;
        this.http = http;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /** Where the bundle lives once installed, resolved from the catalog's relative path. */
    public Path dir(LocalSttModel model) {
        return gameDir.resolve(model.bundleDir());
    }

    /**
     * True when every manifest file is a regular file of exactly the declared size. The SHA-256 is not rechecked
     * here: rehashing a multi-hundred-megabyte model on every readiness query would stall the caller, and a file
     * only reaches this path through a verified install.
     */
    public boolean isInstalled(LocalSttModel model) {
        try {
            validate(model);
        } catch (IllegalArgumentException brokenManifest) {
            return false;
        }
        Path bundle = dir(model);
        for (LocalModelFile file : model.files()) {
            Path path = bundle.resolve(file.file());
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

    /** Installs a bundle without progress reporting. */
    public CompletableFuture<Path> install(LocalSttModel model) {
        return install(model, ProgressListener.none());
    }

    /**
     * Downloads, verifies and publishes one bundle. A second call for the same model joins the running install and
     * completes with it; cancelling either caller's future never cancels the shared install, and progress is not
     * replayed for the late caller. A bundle that is already complete is returned without any request.
     *
     * @param progress called from the HTTP client's threads, possibly with a fraction that does not move when a
     *     retry re-downloads a file
     */
    public CompletableFuture<Path> install(LocalSttModel model, ProgressListener progress) {
        validate(model);
        CompletableFuture<Path> shared;
        synchronized (this) {
            if (isInstalled(model)) {
                return CompletableFuture.completedFuture(dir(model));
            }
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
        return CompletableFuture.runAsync(() -> {
                    // A crash leaves a staging directory behind; it is never trusted, so it is dropped first.
                    try {
                        deleteRecursively(staging);
                        Files.createDirectories(staging);
                    } catch (IOException e) {
                        throw network();
                    }
                })
                .thenCompose(ignored -> download(model, staging, 0, written, total, progress))
                .thenApply(ignored -> publish(staging, bundle))
                .handle((result, error) -> {
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
     * Makes the verified staging files visible. A directory that does not exist yet is renamed in one step, so the
     * bundle appears complete or not at all. An existing directory is the shared legacy model folder or a damaged
     * previous install; its entries are replaced one at a time, each only after its own verified rename.
     */
    private static Path publish(Path staging, Path bundle) {
        try {
            if (!Files.exists(bundle)) {
                Files.createDirectories(bundle.getParent());
                move(staging, bundle);
                return bundle;
            }
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
            return bundle;
        } catch (IOException e) {
            throw network();
        }
    }

    /** The private directory the bundle is assembled in, next to its final location so a rename stays atomic. */
    private Path staging(LocalSttModel model) {
        return dir(model).getParent().resolve(model.id() + STAGING_SUFFIX);
    }

    private URI url(LocalSttModel model, LocalModelFile file) {
        return URI.create(baseUrl + "/" + model.id() + "/" + file.file());
    }

    /** Rejects a manifest that cannot describe a bundle, so a bad catalog entry fails before any request. */
    private static void validate(LocalSttModel model) {
        if (model == null || model.id() == null) {
            throw new IllegalArgumentException("bundle model is missing");
        }
        if (!SAFE_NAME.matcher(model.id()).matches()) {
            throw new IllegalArgumentException("unsafe model id: " + model.id());
        }
        String bundleDir = model.bundleDir();
        if (bundleDir == null || bundleDir.isEmpty()) {
            throw new IllegalArgumentException("bundle directory is empty: " + model.id());
        }
        for (String segment : bundleDir.split("[/\\\\]")) {
            if (!SAFE_NAME.matcher(segment).matches()) {
                throw new IllegalArgumentException("unsafe bundle directory: " + bundleDir);
            }
        }
        List<LocalModelFile> files = model.files();
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("bundle manifest is empty: " + model.id());
        }
        if (files.size() > MAX_FILES) {
            throw new IllegalArgumentException("bundle has too many files: " + files.size());
        }
        Set<String> seen = new HashSet<>();
        long total = 0;
        for (LocalModelFile file : files) {
            if (file == null || !SAFE_NAME.matcher(file.file()).matches()) {
                throw new IllegalArgumentException("unsafe bundle file name: " + file);
            }
            if (!seen.add(file.file())) {
                throw new IllegalArgumentException("duplicate bundle file: " + file.file());
            }
            if (file.size() <= 0 || file.size() > MAX_FILE_BYTES) {
                throw new IllegalArgumentException("bundle file size out of range: " + file);
            }
            if (file.sha256() == null || !SHA256.matcher(file.sha256()).matches()) {
                throw new IllegalArgumentException("bundle file hash is not a SHA-256: " + file.file());
            }
            total += file.size();
            if (total > MAX_BUNDLE_BYTES) {
                throw new IllegalArgumentException("bundle is larger than the cap: " + model.id());
            }
        }
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to);
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
