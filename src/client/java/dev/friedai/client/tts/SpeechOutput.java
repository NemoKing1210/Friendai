package dev.friedai.client.tts;

/** Extension point for text-to-speech. Not used in v1. */
public interface SpeechOutput {
    void speak(String text);
}
