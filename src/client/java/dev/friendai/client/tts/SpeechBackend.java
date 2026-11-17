package dev.friendai.client.tts;

import dev.friendai.features.speech.SpatialAudio;
import dev.friendai.features.speech.tts.PlaybackGate;
import dev.friendai.features.speech.tts.TtsClip;
import java.util.UUID;
import java.util.function.Supplier;

/** One way of making a synthesized clip audible. */
public interface SpeechBackend {
    /**
     * What one clip needs to be played, whichever backend plays it.
     *
     * @param volume the owner's speech volume, 0..1
     * @param device the built-in output device, or null for the system default
     * @param spatial the listener-relative mix of a reply that comes from the companion, or null for a centred one
     * @param companionId the speaking companion, or null when nobody in the world speaks (the settings preview)
     * @param range how far the voice carries, in blocks
     */
    record Request(double volume, String device, Supplier<SpatialAudio.Mix> spatial, UUID companionId, int range) {}

    /** True while this backend can take a clip at all. */
    boolean ready();

    /**
     * Plays a clip admitted by {@code permit} and blocks until it has played or the permit was revoked.
     *
     * @return false when this backend could not take the clip after all and nothing of it was heard
     */
    boolean play(TtsClip clip, Request request, PlaybackGate.Permit permit);
}
