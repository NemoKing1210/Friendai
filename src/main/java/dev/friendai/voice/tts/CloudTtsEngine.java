package dev.friendai.voice.tts;

import dev.friendai.text.UserFacingException;
import dev.friendai.text.UserMessage;
import java.net.http.HttpClient;
import java.util.concurrent.CompletableFuture;

/** A paid provider reached over HTTP. The key travels in a header and never appears in an error. */
public final class CloudTtsEngine implements SpeechEngine {
    private final TtsSettings settings;
    private final TtsProtocol protocol;
    private final TtsHttp http;
    private volatile boolean closed;

    public CloudTtsEngine(TtsSettings settings, TtsProtocol protocol, HttpClient http) {
        this.settings = settings;
        this.protocol = protocol;
        this.http = new TtsHttp(http);
    }

    @Override
    public boolean isReady() {
        return !closed && settings.readinessError() == null;
    }

    @Override
    public CompletableFuture<TtsClip> synthesize(String text) {
        if (closed) {
            return CompletableFuture.failedFuture(uf("friendai.voice.tts.error.failed"));
        }
        UserMessage error = settings.readinessError();
        if (error != null) {
            return CompletableFuture.failedFuture(new UserFacingException(error));
        }
        TtsRequest request = new TtsRequest(
                settings.baseUrl(), settings.apiKey(), settings.model(), settings.voice(), text, settings.speed());
        return http.send(protocol, request);
    }

    @Override
    public void close() {
        closed = true;
    }

    private static UserFacingException uf(String key) {
        return new UserFacingException(UserMessage.of(key));
    }
}
