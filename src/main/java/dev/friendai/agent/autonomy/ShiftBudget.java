package dev.friendai.agent.autonomy;

import dev.friendai.agent.tick.NudgeBackoff;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Keeps director turns affordable: a sliding one-hour window of shifts, and a doubling wait after turns that chose
 * nothing or failed, which stops entirely after {@link NudgeBackoff#MAX_STREAK} of them until the owner speaks.
 */
public final class ShiftBudget {
    static final long HOUR_MILLIS = 3_600_000L;

    private final Deque<Long> recent = new ArrayDeque<>();
    private long lastAttemptMillis = Long.MIN_VALUE;
    private int failStreak;

    /** A director turn was asked for at {@code nowMillis}. */
    public void record(long nowMillis) {
        recent.addLast(nowMillis);
        lastAttemptMillis = nowMillis;
    }

    public boolean allows(long nowMillis, int perHour) {
        while (!recent.isEmpty() && recent.peekFirst() <= nowMillis - HOUR_MILLIS) {
            recent.removeFirst();
        }
        return recent.size() < perHour;
    }

    public void failed() {
        failStreak++;
    }

    public void succeeded() {
        failStreak = 0;
    }

    /** The owner talked to the companion: an exhausted backoff may try again. */
    public void ownerSpoke() {
        failStreak = 0;
    }

    public int failStreak() {
        return failStreak;
    }

    public boolean backoffAllows(long nowMillis, int baseSec) {
        if (failStreak == 0) {
            return true;
        }
        if (NudgeBackoff.exhausted(failStreak)) {
            return false;
        }
        return nowMillis - lastAttemptMillis >= NudgeBackoff.intervalSec(baseSec, failStreak) * 1000L;
    }
}
