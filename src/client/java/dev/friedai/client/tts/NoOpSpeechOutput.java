package dev.friedai.client.tts;

public final class NoOpSpeechOutput implements SpeechOutput {
    @Override
    public void speak(String text) {}
}
