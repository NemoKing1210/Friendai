package dev.friendai.voice.tts;

/** A speech provider's wire format: how to ask for speech and how to read the answer. */
public interface TtsProtocol {
    TtsHttpRequest build(TtsRequest request);

    TtsClip parse(byte[] response);
}
