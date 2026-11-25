package dev.friendai.foundation.diagnostics;

/**
 * What the runner can prove about a wall-clock gap between two ticks. It knows the gap, the work its previous tick
 * measured and how much of the interval the client spent in the pause menu. A slow tick is named first so a gap the
 * runner itself caused keeps its meaning; anything else that the pause does not cover stays unattributed rather than
 * being blamed on the task.
 */
public final class ClockGapPolicy {
    /** A gap shorter than this is an ordinary slow frame, not a stall worth a record. */
    public static final long THRESHOLD_MS = 1_000L;

    /** A tick this short cannot explain a gap even when the wall clock moved by less than that. */
    public static final long MIN_ATTRIBUTABLE_WORK_MS = 250L;

    /** How much of the gap the measured work or the pause may leave unexplained before the reason stays unknown. */
    public static final long GAP_ATTRIBUTION_SLACK_MS = 100L;

    private ClockGapPolicy() {}

    /**
     * The reason for one gap: {@code slow-tick} when the previous tick's work covers it, {@code paused} when the
     * integrated server's pause menu covers it, and {@code unknown} for a freeze, a wall-clock jump or any other gap
     * that leaves no evidence in this process.
     */
    public static String reason(long gapMs, long workMs, long pausedMs) {
        if (workMs >= MIN_ATTRIBUTABLE_WORK_MS && workMs + GAP_ATTRIBUTION_SLACK_MS >= gapMs) return "slow-tick";
        if (pausedMs + GAP_ATTRIBUTION_SLACK_MS >= gapMs) return "paused";
        return "unknown";
    }
}
