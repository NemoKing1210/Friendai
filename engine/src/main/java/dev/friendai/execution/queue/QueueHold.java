package dev.friendai.execution.queue;

/**
 * A local pause or deferral on one job or one plan, additive to the six {@link JobState}s so every existing switch
 * keeps its meaning. A pause never wakes on its own; a defer is eligible when the queue clock reaches its wake tick
 * and its optional condition holds, and it still has to pass the normal guards, dependencies and access checks.
 *
 * <p>{@code wakeTick} is an absolute queue tick. Persistence stores the remaining ticks and rebases them against
 * {@link JobQueue#clock()} on load, so offline wall time never elapses a delay. The record is bounded: a long reason
 * is cut to {@link #MAX_REASON}, and a defer needs at least one wake requirement.
 */
public record QueueHold(Kind kind, String reason, long wakeTick, Guard until) {
    public enum Kind {
        PAUSE,
        DEFER
    }

    /** Longest stored reason; the menu and the prompt both render it. */
    public static final int MAX_REASON = 160;
    /** Longest accepted delay, one hour of simulation time. */
    public static final long MAX_DELAY_TICKS = 72_000L;

    public QueueHold {
        kind = kind == null ? Kind.PAUSE : kind;
        reason = reason == null ? "" : reason.strip();
        if (reason.length() > MAX_REASON) {
            reason = reason.substring(0, MAX_REASON);
        }
        wakeTick = Math.max(0, wakeTick);
        if (kind == Kind.DEFER && wakeTick == 0 && until == null) {
            throw new IllegalArgumentException("a defer needs a delay or a condition");
        }
    }

    /** A manual pause: it is never satisfied by the clock. */
    public static QueueHold pause(String reason) {
        return new QueueHold(Kind.PAUSE, reason, 0, null);
    }

    /** A deferral: one or both of an absolute wake tick and a condition. */
    public static QueueHold defer(String reason, long wakeTick, Guard until) {
        return new QueueHold(Kind.DEFER, reason, wakeTick, until);
    }

    /** Whether a ready check may pass this hold; a pause is always false. */
    public boolean satisfied(long clock, GuardFacts facts) {
        if (kind == Kind.PAUSE) {
            return false;
        }
        if (clock < wakeTick) {
            return false;
        }
        return until == null || until.holds(facts);
    }

    /** Remaining simulation ticks, clamped at zero. */
    public long remaining(long clock) {
        return Math.max(0, wakeTick - clock);
    }

    /** One bounded line for the queue text and the menu. */
    public String describe(long clock) {
        StringBuilder text = new StringBuilder(kind == Kind.PAUSE ? "paused" : "deferred");
        if (!reason.isBlank()) {
            text.append(" (").append(reason).append(')');
        }
        if (kind == Kind.DEFER) {
            long remaining = remaining(clock);
            if (remaining > 0) {
                text.append(" in ").append(remaining).append(" ticks");
            }
            if (until != null) {
                text.append(remaining > 0 ? " and " : " until ").append(until.describe());
            }
        }
        return text.toString();
    }
}
