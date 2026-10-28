package dev.friendai.features.speech.tts;

import java.util.UUID;

/** Decides whether a speech packet should be played here. */
public final class SpeechGate {
    private SpeechGate() {}

    /**
     * @param addressee the player the reply answers, if any
     * @param self      the local player
     * @param speakGuests whether replies to other players are spoken too
     * @param recording whether the local microphone is recording; the companion must never hear itself
     */
    public static boolean shouldSpeak(UUID addressee, UUID self, boolean speakGuests, boolean recording) {
        if (recording || addressee == null || self == null) {
            return false;
        }
        return addressee.equals(self) || speakGuests;
    }
}
