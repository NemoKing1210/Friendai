package dev.friendai.agent.autonomy;

/** Tracks the lifetime of the existing director choice without scheduling work. */
public final class IdleWatchdog {
    public static final int DEFAULT_WINDOW_SEC = 120;

    private IdleWatchdog() {}

    public enum Choice {
        NONE,
        WAITING,
        STALE_IN_FLIGHT,
        UNANSWERED
    }

    /** An active brain retains its tool scope until it settles, even when the choice exceeds its expected window. */
    public static Choice choice(
            boolean awaiting, boolean brainThinking, long requestedAt, long now, int windowSec, long graceMillis) {
        if (!awaiting) return Choice.NONE;
        if (brainThinking) {
            return requestedAt > 0 && now - requestedAt >= Math.max(1, windowSec) * 1000L
                    ? Choice.STALE_IN_FLIGHT
                    : Choice.WAITING;
        }
        return requestedAt <= 0 || now - requestedAt >= graceMillis ? Choice.UNANSWERED : Choice.WAITING;
    }
}
