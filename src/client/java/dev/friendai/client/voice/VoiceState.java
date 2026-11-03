package dev.friendai.client.voice;

import dev.friendai.features.speech.TranscriptText;
import dev.friendai.foundation.text.UserMessage;

/** What the voice pipeline is doing, for the HUD. */
public final class VoiceState {
    public enum Kind {
        IDLE,
        RECORDING,
        TRANSCRIBING,
        ERROR
    }

    /** Longest recognition partial the HUD keeps; the recognizer caps before publishing, this is the display bound. */
    public static final int MAX_PARTIAL_CHARS = 240;

    /** @param partial replaceable local recognition text during recording; never a translation key or a turn */
    public record Snapshot(Kind kind, UserMessage message, long sinceMillis, String partial) {}

    private static volatile Snapshot current = new Snapshot(Kind.IDLE, null, System.currentTimeMillis(), null);

    private VoiceState() {}

    public static void set(Kind kind, UserMessage message) {
        current = new Snapshot(kind, message, System.currentTimeMillis(), null);
    }

    /**
     * Replaces the recognition text shown while recording. Stale or blank updates are ignored, and the text is
     * capped by code points so a long decode never grows the HUD without bound.
     */
    public static void setPartial(String partial) {
        Snapshot shown = current;
        if (shown.kind() != Kind.RECORDING) {
            return;
        }
        String capped = TranscriptText.cap(partial, MAX_PARTIAL_CHARS);
        if (capped.isEmpty() || capped.equals(shown.partial())) {
            return;
        }
        current = new Snapshot(Kind.RECORDING, null, shown.sinceMillis(), capped);
    }

    public static Snapshot current() {
        return current;
    }
}
