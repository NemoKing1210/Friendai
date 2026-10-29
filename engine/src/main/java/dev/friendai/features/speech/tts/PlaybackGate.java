package dev.friendai.features.speech.tts;

/**
 * Keeps a stop that arrives while an audio line is still opening from letting that clip play anyway: the line
 * is slow to open, so a playback takes a token first and asks again, under the playback lock, before it starts
 * writing. One stop invalidates every token handed out before it.
 */
public final class PlaybackGate {
    /** An immutable admission permit captured before asynchronous work, so a later stop can reject it. */
    public record Permit(long token) {}

    private long epoch;

    /** A token for a playback that is about to open its line. */
    public synchronized long token() {
        return epoch;
    }

    /** Captures the admission permit before synthesis, not at the moment the device is opened. */
    public synchronized Permit permit() {
        return new Permit(epoch);
    }

    /** Invalidates every token handed out so far. */
    public synchronized void cancel() {
        epoch++;
    }

    /** True while no {@link #cancel()} has run since {@code token} was taken. */
    public synchronized boolean stillOpen(long token) {
        return token == epoch;
    }

    /** True while no {@link #cancel()} has run since the permit was taken. */
    public synchronized boolean stillOpen(Permit permit) {
        return permit != null && permit.token() == epoch;
    }
}
