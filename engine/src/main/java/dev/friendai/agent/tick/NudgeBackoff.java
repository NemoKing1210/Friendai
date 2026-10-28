package dev.friendai.agent.tick;

/**
 * Keeps an unanswered goal nudge from costing a model request every interval forever: the wait doubles with each
 * nudge that led to nothing, and after {@link #MAX_STREAK} of them the nudges stop until the owner speaks or the
 * companion starts working again.
 */
public final class NudgeBackoff {
    public static final int MAX_STREAK = 6;
    private static final int MAX_DOUBLINGS = 4;
    private static final int MAX_INTERVAL_SEC = 3_600;

    private NudgeBackoff() {}

    /** The wait before the next nudge, given how many went unanswered so far. */
    public static int intervalSec(int baseSec, int streak) {
        long scaled = (long) baseSec << Math.min(Math.max(streak, 0), MAX_DOUBLINGS);
        return (int) Math.min(scaled, MAX_INTERVAL_SEC);
    }

    public static boolean exhausted(int streak) {
        return streak >= MAX_STREAK;
    }
}
