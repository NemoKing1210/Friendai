package dev.friendai.voice.tts;

import dev.friendai.text.UserFacingException;
import dev.friendai.text.UserMessage;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/** Sends one speech request and maps failures to a single player-facing message. Never retries. */
public final class TtsHttp {
    static final int MAX_RESPONSE_BYTES = 10 * 1024 * 1024;
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private final HttpClient client;

    public TtsHttp(HttpClient client) {
        this.client = client;
    }

    public CompletableFuture<TtsClip> send(TtsProtocol protocol, TtsRequest request) {
        TtsHttpRequest spec;
        try {
            spec = protocol.build(request);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e instanceof UserFacingException ? e : new UserFacingException(
                    UserMessage.of("friendai.voice.tts.error.failed")));
        }
        HttpRequest httpRequest;
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(spec.uri())
                    .timeout(TIMEOUT)
                    .method(spec.method(), HttpRequest.BodyPublishers.ofByteArray(spec.body()));
            spec.headers().forEach(builder::header);
            httpRequest = builder.build();
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(uf("friendai.voice.tts.error.failed"));
        }
        return client.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofByteArray())
                .thenApply(response -> decode(protocol, response))
                .exceptionallyCompose(error -> CompletableFuture.failedFuture(mapFailure(error)));
    }

    private static TtsClip decode(TtsProtocol protocol, HttpResponse<byte[]> response) {
        int status = response.statusCode();
        if (status == 401 || status == 403) {
            throw uf("friendai.voice.tts.error.auth");
        }
        if (status == 429) {
            throw uf("friendai.voice.tts.error.quota");
        }
        if (status < 200 || status >= 300) {
            throw uf("friendai.voice.tts.error.failed");
        }
        byte[] body = response.body();
        if (body == null || body.length > MAX_RESPONSE_BYTES) {
            throw uf("friendai.voice.tts.error.failed");
        }
        try {
            return protocol.parse(body);
        } catch (UserFacingException e) {
            throw e;
        } catch (RuntimeException e) {
            throw uf("friendai.voice.tts.error.failed");
        }
    }

    private static Throwable mapFailure(Throwable error) {
        Throwable cause = error;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause instanceof UserFacingException ? cause : uf("friendai.voice.tts.error.network");
    }

    private static UserFacingException uf(String key) {
        return new UserFacingException(UserMessage.of(key));
    }
}
