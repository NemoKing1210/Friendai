package dev.friendai.features.speech.tts;

/**
 * Decides when the next frame may be handed to a real-time sink that queues only a few of them. Frames follow an
 * absolute schedule, so the jitter of one sleep never accumulates: a sender that fell a little behind catches up
 * at once, and one that stalled for longer restarts the schedule instead of flooding the sink's bounded queue.
 * Not thread-safe; one pacer belongs to one sender.
 */
public final class FramePacer {
    private final long frameNanos;
    private final long maxLagNanos;
    private boolean started;
    private long due;

    /**
     * @param frameNanos how long one frame sounds
     * @param maxLagFrames how many frames a late sender may still send back to back
     */
    public FramePacer(long frameNanos, int maxLagFrames) {
        if (frameNanos <= 0 || maxLagFrames < 0) {
            throw new IllegalArgumentException("frame length must be positive and the lag bound not negative");
        }
        this.frameNanos = frameNanos;
        this.maxLagNanos = frameNanos * maxLagFrames;
    }

    /** Nanoseconds to wait before the next frame may be sent at {@code now}; zero means send it now. */
    public long waitNanos(long now) {
        if (!started) {
            started = true;
            due = now;
            return 0;
        }
        long lag = now - due;
        if (lag > maxLagNanos) {
            due = now;
            return 0;
        }
        return Math.max(0, -lag);
    }

    /** Records that a frame was sent: the next one is due one frame later. */
    public void sent() {
        due += frameNanos;
    }
}
