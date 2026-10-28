package dev.friendai.voice;

import dev.friendai.text.UserFacingException;
import dev.friendai.text.UserMessage;
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
import java.util.concurrent.CompletableFuture;
import java.util.function.DoubleConsumer;

/** Downloads speech models into a directory, verifying size and SHA-256 before they become visible. */
public final class WhisperModelManager {
    private final Path dir;
    private final HttpClient http;
    private final String baseUrl;

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

    /** @param progress called with a fraction in [0, 1] as bytes arrive, from a background thread */
    public CompletableFuture<Path> download(ModelSpec model, DoubleConsumer progress) {
        if (isInstalled(model)) {
            return CompletableFuture.completedFuture(path(model));
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/" + model.file()))
                .GET()
                .build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
                .thenApplyAsync(response -> {
                    if (response.statusCode() != 200) {
                        closeQuietly(response.body());
                        throw new UserFacingException(UserMessage.of("friendai.error.llm.network"));
                    }
                    return save(model, response.body(), progress);
                });
    }

    private Path save(ModelSpec model, InputStream body, DoubleConsumer progress) {
        Path part = dir.resolve(model.file() + ".part");
        try (InputStream in = body) {
            Files.createDirectories(dir);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long total = 0;
            try (OutputStream out =
                    Files.newOutputStream(part, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                byte[] buffer = new byte[64 * 1024];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    out.write(buffer, 0, n);
                    digest.update(buffer, 0, n);
                    total += n;
                    progress.accept(Math.min(1.0, (double) total / model.size()));
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
