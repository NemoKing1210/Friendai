package dev.friendai.voice;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.friendai.text.UserFacingException;
import dev.friendai.text.UserMessage;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * One-shot HTTP for speech services. Billable requests are never retried automatically,
 * and failures never carry the response body or request headers.
 */
public final class VoiceHttp {
    /** An answer larger than this is rejected after it has been read into memory; a size sanity check, not a stream guard. */
    static final int MAX_RESPONSE_BYTES = 10 * 1024 * 1024;

    private final HttpClient client;
    private final Duration timeout;

    /** @param timeout per-request timeout; the caller picks it, since STT and TTS need different budgets */
    public VoiceHttp(HttpClient client, Duration timeout) {
        this.client = client;
        this.timeout = timeout;
    }

    public CompletableFuture<JsonObject> postJson(PreparedRequest request) {
        return send(request).thenApply(body -> {
            try {
                return JsonParser.parseString(new String(body, StandardCharsets.UTF_8))
                        .getAsJsonObject();
            } catch (RuntimeException e) {
                throw failed();
            }
        });
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
        return client.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofByteArray())
                .handle((response, error) -> {
                    if (error != null) {
                        throw VoiceErrors.network();
                    }
                    if (response.statusCode() / 100 != 2) {
                        throw VoiceErrors.forStatus(response.statusCode());
                    }
                    byte[] body = response.body();
                    if (body == null || body.length > MAX_RESPONSE_BYTES) {
                        throw failed();
                    }
                    return body;
                });
    }

    private static UserFacingException failed() {
        return new UserFacingException(UserMessage.of("friendai.voice.error.failed"));
    }
}
