package dev.friendai.client.voice;

import dev.friendai.features.speech.wake.WakeModelCatalog;
import dev.friendai.features.speech.wake.WakeModelCatalog.WakeFile;
import dev.friendai.features.speech.wake.WakeModelCatalog.WakeModel;
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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Installs and verifies the one local wake-word bundle {@link WakeModelCatalog} describes.
 *
 * <p>The bundle is small (under 7 MB) and there is exactly one of it, so this is deliberately simpler than the
 * multi-model speech manager: each file is streamed to {@code <name>.part} while being hashed, the declared size and
 * SHA-256 must match exactly before it is renamed into place, and {@code keywords.txt} is written from the
 * catalogue's own token lines rather than downloaded. A file that is already present with the right size and hash is
 * adopted, so a completed installation never re-downloads.
 *
 * <p>Nothing here touches a native library or the client thread: callers get a future, and the one worker thread is
 * a daemon so a hanging request can never keep the game from closing.
 */
public final class WakeBundleManager {
    private static final String PART_SUFFIX = ".part";
    private static final String MARKER_PREFIX = ".friendai-wake-";
    private static final int BUFFER_BYTES = 64 * 1024;
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(10);

    private final Path gameDir;
    private final HttpClient http;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "friendai-wake");
        thread.setDaemon(true);
        return thread;
    });
    /** Bundles whose bytes were hashed in this process; re-hashing on every arm is wasted work. */
    private final Set<String> verified = ConcurrentHashMap.newKeySet();

    private final Map<String, CompletableFuture<Path>> inFlight = new ConcurrentHashMap<>();

    public WakeBundleManager(Path gameDir, HttpClient http) {
        this.gameDir = gameDir;
        this.http = http;
    }

    /** Where the bundle lives once installed. */
    public Path dir(WakeModel model) {
        return gameDir.resolve(model.bundleDir());
    }

    /** One manifest file of an installed bundle. */
    public Path file(WakeModel model, WakeFile file) {
        return dir(model).resolve(file.file());
    }

    /** True when the marker matches and every file has its declared size; cheap enough for a settings screen. */
    public boolean hasFiles(WakeModel model) {
        if (!markerMatches(model)) {
            return false;
        }
        for (WakeFile file : model.files()) {
            Path path = file(model, file);
            try {
                if (!Files.isRegularFile(path) || Files.size(path) != file.size()) {
                    return false;
                }
            } catch (IOException unreadable) {
                return false;
            }
        }
        Path keywords = dir(model).resolve(WakeModelCatalog.KEYWORDS_FILE);
        try {
            return Files.isRegularFile(keywords)
                    && Files.readString(keywords, StandardCharsets.UTF_8).equals(model.keywordsText());
        } catch (IOException unreadable) {
            return false;
        }
    }

    /**
     * Hashes every file before a native model is loaded, so a corrupt or substituted bundle fails as a player-facing
     * "no model" instead of a native crash. The result is cached for the rest of the process.
     */
    public boolean verifyInstalled(WakeModel model) {
        String key = model.id() + "@" + model.revision();
        if (verified.contains(key)) {
            return true;
        }
        if (!hasFiles(model)) {
            return false;
        }
        for (WakeFile file : model.files()) {
            if (!hashMatches(file(model, file), file)) {
                return false;
            }
        }
        verified.add(key);
        return true;
    }

    /** Installs the bundle if needed; a second caller joins the running install instead of starting a second one. */
    public CompletableFuture<Path> install(WakeModel model) {
        synchronized (inFlight) {
            CompletableFuture<Path> running = inFlight.get(model.id());
            if (running != null && !running.isDone()) {
                return running;
            }
            CompletableFuture<Path> created = CompletableFuture.supplyAsync(() -> installNow(model), worker);
            inFlight.put(model.id(), created);
            created.whenComplete((ignored, error) -> inFlight.remove(model.id(), created));
            return created;
        }
    }

    /** Releases the installer's worker; a completed download stays on disk and is adopted next launch. */
    public void close() {
        worker.shutdownNow();
    }

    private Path installNow(WakeModel model) {
        Path bundle = dir(model);
        try {
            Files.createDirectories(bundle);
        } catch (IOException e) {
            throw network();
        }
        if (verifyInstalled(model)) {
            return bundle;
        }
        for (WakeFile file : model.files()) {
            Path target = bundle.resolve(file.file());
            if (hashMatches(target, file)) {
                continue;
            }
            download(model, file, target);
        }
        writeKeywords(model, bundle);
        writeMarker(model, bundle);
        if (!verifyInstalled(model)) {
            throw corrupt();
        }
        return bundle;
    }

    private void download(WakeModel model, WakeFile file, Path target) {
        URI uri = URI.create(model.downloadBaseUrl() + "/" + file.file());
        Path part = target.resolveSibling(target.getFileName() + PART_SUFFIX);
        HttpRequest request =
                HttpRequest.newBuilder(uri).timeout(REQUEST_TIMEOUT).GET().build();
        HttpResponse<InputStream> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw network();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw network();
        }
        if (response.statusCode() != 200) {
            closeQuietly(response.body());
            throw network();
        }
        MessageDigest digest = sha256();
        long written = 0;
        try (InputStream in = response.body();
                OutputStream out = Files.newOutputStream(part)) {
            byte[] buffer = new byte[BUFFER_BYTES];
            int read;
            while ((read = in.read(buffer)) > 0) {
                written += read;
                // One byte past the declared size is already a broken or hostile answer; stop before it fills a disk.
                if (written > file.size()) {
                    throw corrupt();
                }
                digest.update(buffer, 0, read);
                out.write(buffer, 0, read);
            }
        } catch (IOException e) {
            deleteQuietly(part);
            throw network();
        }
        if (written != file.size() || !HexFormat.of().formatHex(digest.digest()).equals(file.sha256())) {
            deleteQuietly(part);
            throw corrupt();
        }
        publish(part, target);
    }

    private void writeKeywords(WakeModel model, Path bundle) {
        Path target = bundle.resolve(WakeModelCatalog.KEYWORDS_FILE);
        Path part = bundle.resolve(WakeModelCatalog.KEYWORDS_FILE + PART_SUFFIX);
        try {
            Files.writeString(part, model.keywordsText(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            deleteQuietly(part);
            throw network();
        }
        publish(part, target);
    }

    private void writeMarker(WakeModel model, Path bundle) {
        Path marker = bundle.resolve(markerName(model));
        try {
            Files.writeString(marker, model.revision() + "\n" + model.id(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw network();
        }
    }

    private boolean markerMatches(WakeModel model) {
        Path marker = dir(model).resolve(markerName(model));
        try {
            return Files.isRegularFile(marker)
                    && Files.readString(marker, StandardCharsets.UTF_8)
                            .strip()
                            .replace("\r\n", "\n")
                            .equals(model.revision() + "\n" + model.id());
        } catch (IOException unreadable) {
            return false;
        }
    }

    private static String markerName(WakeModel model) {
        return MARKER_PREFIX + model.id() + ".txt";
    }

    private static boolean hashMatches(Path path, WakeFile file) {
        try {
            if (!Files.isRegularFile(path) || Files.size(path) != file.size()) {
                return false;
            }
            MessageDigest digest = sha256();
            try (InputStream in = Files.newInputStream(path)) {
                byte[] buffer = new byte[BUFFER_BYTES];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest()).equals(file.sha256());
        } catch (IOException unreadable) {
            return false;
        }
    }

    private static void publish(Path part, Path target) {
        try {
            try {
                Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException noAtomicMove) {
                Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            deleteQuietly(part);
            throw network();
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available", impossible);
        }
    }

    private static void closeQuietly(InputStream stream) {
        try {
            stream.close();
        } catch (IOException ignored) {
            // The response is being discarded anyway.
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // A leftover part file is overwritten by the next attempt.
        }
    }

    private static UserFacingException network() {
        return new UserFacingException(UserMessage.of("friendai.voice.error.network"));
    }

    private static UserFacingException corrupt() {
        return new UserFacingException(UserMessage.of("friendai.voice.error.model_corrupt"));
    }
}
