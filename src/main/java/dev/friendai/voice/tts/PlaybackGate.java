package dev.friendai.voice.tts;

/**
 * Keeps a stop that arrives while an audio line is still opening from letting that clip play anyway: the line
 * is slow to open, so a playback takes a token first and asks again, under the playback lock, before it starts
 * writing. One stop invalidates every token handed out before it.
 */
public final class PlaybackGate {
    private long epoch;

    /** A token for a playback that is about to open its line. */
    public synchronized long token() {
        return epoch;
    }

    /** Invalidates every token handed out so far. */
    public synchronized void cancel() {
        epoch++;
    }

    /** True while no {@link #cancel()} has run since {@code token} was taken. */
    public synchronized boolean stillOpen(long token) {
        return token == epoch;
    }
}
