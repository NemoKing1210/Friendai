package dev.friendai.client.voice;

import dev.friendai.features.speech.PcmConverter;
import dev.friendai.features.speech.ResolvedStt;
import dev.friendai.features.speech.WavCodec;
import dev.friendai.infrastructure.speech.SttClient;
import java.util.concurrent.CompletableFuture;

/** A cloud recognizer: the recording becomes a WAV for OpenAI-compatible APIs or raw PCM for Yandex. */
final class CloudStt implements SpeechToText {
    /** The microphone records at this rate; the WAV header and Yandex query both declare it. */
    static final int SAMPLE_RATE = 16000;

    private final ResolvedStt settings;
    private final SttClient client;

    CloudStt(ResolvedStt settings, SttClient client) {
        this.settings = settings;
        this.client = client;
    }

    @Override
    public boolean isReady() {
        return true;
    }

    @Override
    public CompletableFuture<String> transcribe(float[] samples16k, String language) {
        byte[] pcm16k = PcmConverter.fromFloat(samples16k);
        return client.transcribe(settings, WavCodec.encode(pcm16k, SAMPLE_RATE), pcm16k);
    }
}
