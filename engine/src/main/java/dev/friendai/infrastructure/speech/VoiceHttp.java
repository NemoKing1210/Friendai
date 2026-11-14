package dev.friendai.infrastructure.speech;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.friendai.features.speech.PreparedRequest;
import dev.friendai.features.speech.VoiceErrors;
import dev.friendai.foundation.concurrent.CancelLink;
import dev.friendai.foundation.text.UserFacingException;
import dev.friendai.foundation.text.UserMessage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One-shot HTTP for speech services. Billable requests are never retried automatically,
 * and failures never carry the response body or request headers.
 *
 * <p>The response is read as a stream with a hard byte cap, so a chunked or oversized answer is refused while it
 * arrives instead of after it has been buffered. Cancelling the returned future cancels the exchange, which is what
 * lets a stopped reply stop paying for an answer nobody will hear.
 */
public final class VoiceHttp {
    /** An answer larger than this is rejected; it is read in bounded pieces, not buffered first. */
    static final int MAX_RESPONSE_BYTES = 10 * 1024 * 1024;

    private static final int READ_CHUNK_BYTES = 8 * 1024;

    /** Body reads never block the HTTP client's own threads or a caller waiting for a reply. */
    private static final ExecutorService READERS = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "friendai-voice-body");
        thread.setDaemon(true);
        return thread;
    });

    private final HttpClient client;
    private final Duration timeout;

    /** @param timeout per-request timeout; the caller picks it, since STT and TTS need different budgets */
    public VoiceHttp(HttpClient client, Duration timeout) {
        this.client = client;
        this.timeout = timeout;
    }

    public CompletableFuture<JsonObject> postJson(PreparedRequest request) {
        CompletableFuture<byte[]> body = send(request);
        CompletableFuture<JsonObject> parsed = body.thenApply(bytes -> {
            try {
                return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8))
                        .getAsJsonObject();
            } catch (RuntimeException e) {
                throw failed();
            }
        });
        CancelLink.link(parsed, body);
        return parsed;
    }

    public CompletableFuture<byte[]> postBytes(PreparedRequest request) {
        return send(request);
    }

    private CompletableFuture<byte[]> send(PreparedRequest request) {
        HttpRequest httpRequest;
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(request.uri())
                    .timeout(timeout)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(request.body()));
            if (request.contentType() != null) {
                builder.header("Content-Type", request.contentType());
            }
            request.headers().forEach(builder::header);
            httpRequest = builder.build();
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(failed());
        }
        CompletableFuture<HttpResponse<InputStream>> exchange =
                client.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
        CompletableFuture<byte[]> body = new CompletableFuture<>();
        exchange.whenComplete((response, error) -> {
            if (error != null) {
                Throwable cause =
                        error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
                body.completeExceptionally(
                        cause instanceof CancellationException cancelled ? cancelled : VoiceErrors.network());
                return;
            }
            InputStream stream = response.body();
            // A reply the caller abandoned must close its stream, or the provider keeps writing into a socket
            // nobody reads until the whole answer has been paid for.
            body.whenComplete((ignored, cancelled) -> {
                if (body.isCancelled()) {
                    closeQuietly(stream);
                }
            });
            READERS.execute(() -> {
                try {
                    body.complete(read(response));
                } catch (Throwable failure) {
                    body.completeExceptionally(failure);
                }
            });
        });
        body.whenComplete((value, error) -> {
            if (body.isCancelled()) {
                exchange.cancel(true);
            }
        });
        return body;
    }

    private static void closeQuietly(InputStream stream) {
        try {
            stream.close();
        } catch (IOException ignored) {
            // The stream is already gone, which is what closing it wanted.
        }
    }

    private static byte[] read(HttpResponse<InputStream> response) {
        try (InputStream in = response.body()) {
            if (response.statusCode() / 100 != 2) {
                // The body is closed unread: an error answer is never copied into a message.
                throw VoiceErrors.forStatus(response.statusCode());
            }
            long declared =
                    response.headers().firstValueAsLong("content-length").orElse(-1L);
            if (declared > MAX_RESPONSE_BYTES) {
                throw failed();
            }
            int expected = declared > 0 ? (int) declared : READ_CHUNK_BYTES;
            ByteArrayOutputStream out = new ByteArrayOutputStream(expected);
            byte[] buffer = new byte[READ_CHUNK_BYTES];
            int total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > MAX_RESPONSE_BYTES) {
                    throw failed();
                }
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } catch (UserFacingException e) {
            throw e;
        } catch (IOException e) {
            throw VoiceErrors.network();
        } catch (RuntimeException e) {
            throw failed();
        }
    }

    private static UserFacingException failed() {
        return new UserFacingException(UserMessage.of("friendai.voice.error.failed"));
    }
}
