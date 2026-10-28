package dev.friendai.features.speech.tts;

import dev.friendai.features.speech.PcmAudio;
import dev.friendai.features.speech.PreparedRequest;
import dev.friendai.features.speech.ResolvedTts;

/** One speech-synthesis wire format. */
public interface TtsProtocol {
    PreparedRequest prepare(ResolvedTts settings, String text);

    PcmAudio parse(byte[] body, ResolvedTts settings);
}
