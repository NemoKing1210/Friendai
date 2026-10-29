package dev.friendai.client.audio;

import dev.friendai.foundation.text.UserFacingException;
import dev.friendai.foundation.text.UserMessage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.DoubleConsumer;

/** Downloads speech models into a directory, verifying size and SHA-256 before they become visible. */
public final class WhisperModelManager {
    private final Path dir;
    private final HttpClient http;
    private final String baseUrl;
    /** One running download per model; a second caller joins it instead of truncating its staging file. */
    private final Map<ModelSpec, CompletableFuture<Path>> inFlight = new ConcurrentHashMap<>();

    private final AtomicLong operations = new AtomicLong();

    public WhisperModelManager(Path dir, HttpClient http, String baseUrl) {
        this.dir = dir;
        this.http = http;
        this.baseUrl = baseUrl;
    }

    public Path path(ModelSpec model) {
        return dir.resolve(model.file());
    }

    public boolean isInstalled(ModelSpec model) {
        try {
            return Files.isRegularFile(path(model)) && Files.size(path(model)) == model.size();
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Downloads one model. A second call for the same model joins the running operation and completes with it;
     * cancelling either caller's future does not cancel the shared download, and its progress is not replayed.
     *
     * @param progress called with a fraction in [0, 1] as bytes arrive, from a background thread
     */
    public CompletableFuture<Path> download(ModelSpec model, DoubleConsumer progress) {
        CompletableFuture<Path> shared;
        synchronized (this) {
            if (isInstalled(model)) {
                return CompletableFuture.completedFuture(path(model));
            }
            shared = inFlight.get(model);
            if (shared == null || shared.isDone()) {
                CompletableFuture<Path> created = start(model, progress);
                inFlight.put(model, created);
                created.whenComplete((ignored, error) -> inFlight.remove(model, created));
                shared = created;
            }
        }
        // Each caller owns a plain relay future: cancelling it never cancels the shared operation or another caller.
        CompletableFuture<Path> caller = new CompletableFuture<>();
        shared.whenComplete((result, error) -> {
            if (error != null) caller.completeExceptionally(error);
            else caller.complete(result);
        });
        return caller;
    }

    private CompletableFuture<Path> start(ModelSpec model, DoubleConsumer progress) {
        long operation = operations.incrementAndGet();
        Path part = dir.resolve(model.file() + ".part-" + operation);
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/" + model.file()))
                .GET()
                .build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
                .thenApplyAsync(response -> {
                    if (response.statusCode() != 200) {
                        closeQuietly(response.body());
                        throw new UserFacingException(UserMessage.of("friendai.error.llm.network"));
                    }
                    return save(model, response.body(), progress, part);
                });
    }

    private Path save(ModelSpec model, InputStream body, DoubleConsumer progress, Path part) {
        try (InputStream in = body) {
            Files.createDirectories(dir);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long total = 0;
            try (OutputStream out =
                    Files.newOutputStream(part, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                byte[] buffer = new byte[64 * 1024];
                long limit = model.size();
                while (total <= limit) {
                    // One byte past the declared size is enough to detect a longer body without reading it.
                    int allowed = (int) Math.min(buffer.length, limit + 1 - total);
                    int n = in.read(buffer, 0, allowed);
                    if (n < 0) break;
                    total += n;
                    if (total > limit) break;
                    out.write(buffer, 0, n);
                    digest.update(buffer, 0, n);
                    progress.accept(Math.min(1.0, (double) total / Math.max(1L, limit)));
                }
            }
            if (total != model.size()
                    || !HexFormat.of().formatHex(digest.digest()).equalsIgnoreCase(model.sha256())) {
                Files.deleteIfExists(part);
                throw new UserFacingException(UserMessage.of("friendai.voice.error.model_corrupt"));
            }
            Files.move(part, path(model), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return path(model);
        } catch (IOException | NoSuchAlgorithmException e) {
            try {
                Files.deleteIfExists(part);
            } catch (IOException ignored) {
                // nothing more to clean up
            }
            throw new UserFacingException(UserMessage.of("friendai.error.llm.network"));
        }
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException ignored) {
            // nothing to do
        }
    }
}
