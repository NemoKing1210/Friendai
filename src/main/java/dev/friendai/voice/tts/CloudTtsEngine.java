package dev.friendai.voice.tts;

import dev.friendai.text.UserFacingException;
import dev.friendai.text.UserMessage;
import dev.friendai.voice.ResolvedTts;
import dev.friendai.voice.VoiceErrors;
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
                .handle((audio, error) -> {
                    if (error != null) {
                        throw synthesisFailure(error);
                    }
                    return new TtsClip(audio.data(), audio.sampleRateHz());
                });
    }

    /**
     * The shared HTTP layer reports the recognition keys; a failed synthesis must read as a
     * synthesis failure instead. Readiness and protocol errors keep their own message.
     */
    private static UserFacingException synthesisFailure(Throwable error) {
        UserMessage message = VoiceErrors.userMessage(error);
        String key = switch (message.key()) {
            case "friendai.voice.error.failed" -> "friendai.voice.tts.error.failed";
            case "friendai.voice.error.auth" -> "friendai.voice.tts.error.auth";
            case "friendai.voice.error.quota" -> "friendai.voice.tts.error.quota";
            case "friendai.voice.error.network" -> "friendai.voice.tts.error.network";
            default -> message.key();
        };
        return new UserFacingException(key.equals(message.key()) ? message : UserMessage.of(key));
    }

    @Override
    public void close() {
        closed = true;
    }
}
