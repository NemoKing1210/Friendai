package dev.friendai.voice.tts;

import dev.friendai.voice.PcmAudio;
import dev.friendai.voice.PreparedRequest;
import dev.friendai.voice.ResolvedTts;

/** One speech-synthesis wire format. */
public interface TtsProtocol {
    PreparedRequest prepare(ResolvedTts settings, String text);

    PcmAudio parse(byte[] body, ResolvedTts settings);
}
