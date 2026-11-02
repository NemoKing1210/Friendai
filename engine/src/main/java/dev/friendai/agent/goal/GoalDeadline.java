package dev.friendai.agent.goal;

/**
 * The optional goal deadline in game ticks. Absolute ticks are stored on the goal; this converts the owner's minutes
 * into ticks and back into the short phrase the prompt shows. Pure and never stored.
 */
public final class GoalDeadline {
    /** Twenty ticks a second, sixty seconds a minute. */
    public static final long TICKS_PER_MINUTE = 1200L;

    /** One week, the longest deadline a goal may carry. */
    public static final int MAX_MINUTES = 10_080;

    private GoalDeadline() {}

    /** The absolute tick a deadline set {@code minutes} from now ends at; zero or less means no deadline. */
    public static long tickFrom(long nowTick, long minutes) {
        return minutes <= 0 ? 0L : nowTick + minutes * TICKS_PER_MINUTE;
    }

    /**
     * A short phrase for the goal line: empty without a deadline, otherwise {@code due in N minutes} while the
     * deadline is ahead and {@code overdue by N minutes} once it has passed. Whole minutes, never zero.
     */
    public static String describe(long deadlineTick, long nowTick) {
        if (deadlineTick <= 0) {
            return "";
        }
        long ticks = deadlineTick - nowTick;
        return ticks > 0 ? "due in " + minutes(ticks) + " minutes" : "overdue by " + minutes(-ticks) + " minutes";
    }

    /** Minutes rounded away from zero, so a deadline one tick away still reads as a minute. */
    private static long minutes(long ticks) {
        return Math.max(1L, (ticks + TICKS_PER_MINUTE - 1) / TICKS_PER_MINUTE);
    }
}
