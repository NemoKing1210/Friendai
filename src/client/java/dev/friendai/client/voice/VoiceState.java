package dev.friendai.client.voice;

import dev.friendai.foundation.text.UserMessage;

/** What the voice pipeline is doing, for the HUD. */
public final class VoiceState {
    public enum Kind {
        IDLE,
        RECORDING,
        TRANSCRIBING,
        ERROR
    }

    public record Snapshot(Kind kind, UserMessage message, long sinceMillis) {}

    private static volatile Snapshot current = new Snapshot(Kind.IDLE, null, System.currentTimeMillis());

    private VoiceState() {}

    public static void set(Kind kind, UserMessage message) {
        current = new Snapshot(kind, message, System.currentTimeMillis());
    }

    public static Snapshot current() {
        return current;
    }
}
