package dev.friendai.features.speech.tts;

import java.util.concurrent.CompletableFuture;

/** Turns text into PCM audio. One engine is built for one resolved configuration and reused for its clips. */
public interface SpeechEngine extends AutoCloseable {
    /** True when this engine can synthesize right now: voice installed or key present, and not closed. */
    boolean isReady();

    /** Runs on the engine's own executor. Failures complete exceptionally with a {@code UserFacingException}. */
    CompletableFuture<TtsClip> synthesize(String text);

    @Override
    void close();
}
