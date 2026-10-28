package dev.friendai.features.speech.stt;

import com.google.gson.JsonObject;
import dev.friendai.features.speech.PreparedRequest;
import dev.friendai.features.speech.ResolvedStt;

/** One speech-recognition wire format. */
public interface SttProtocol {
    /** @param wav16k the recording as a WAV file; @param pcm16k the same audio as raw 16 kHz PCM */
    PreparedRequest prepare(ResolvedStt settings, byte[] wav16k, byte[] pcm16k);

    /**
     * Extracts the transcript from the provider answer.
     *
     * @throws dev.friendai.foundation.text.UserFacingException when the answer carries no usable text
     */
    String parse(JsonObject response);
}
