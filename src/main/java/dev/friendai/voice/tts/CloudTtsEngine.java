package dev.friendai.voice.tts;

import dev.friendai.text.UserFacingException;
import dev.friendai.text.UserMessage;
import dev.friendai.voice.ResolvedTts;
import java.util.concurrent.CompletableFuture;

/** A paid provider reached over HTTP. The key travels in a header and never appears in an error. */
public final class CloudTtsEngine implements SpeechEngine {
    private final ResolvedTts settings;
    private final TtsClient client;
    private volatile boolean closed;

    public CloudTtsEngine(ResolvedTts settings, TtsClient client) {
        this.settings = settings;
        this.client = client;
    }

    @Override
    public boolean isReady() {
        return !closed;
    }

    @Override
    public CompletableFuture<TtsClip> synthesize(String text) {
        if (closed) {
            return CompletableFuture.failedFuture(
                    new UserFacingException(UserMessage.of("friendai.voice.tts.error.failed")));
        }
        return client.synthesize(settings, text)
                .thenApply(audio -> new TtsClip(audio.data(), audio.sampleRateHz()));
    }

    @Override
    public void close() {
        closed = true;
    }
}
