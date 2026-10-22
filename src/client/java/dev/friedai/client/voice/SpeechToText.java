package dev.friedai.client.voice;

import java.util.concurrent.CompletableFuture;

public interface SpeechToText {
    boolean isReady();

    /** @param samples16k mono samples at 16 kHz in [-1, 1) */
    CompletableFuture<String> transcribe(float[] samples16k, String language);
}
