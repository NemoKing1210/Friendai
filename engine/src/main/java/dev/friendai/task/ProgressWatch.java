package dev.friendai.task;

/**
 * Detects a task that is alive but has stopped making real progress. Pure, allocation-free on the common path, and
 * driven by active ticks rather than a wall clock.
 */
public final class ProgressWatch {
    public static final long UNKNOWN = Long.MIN_VALUE;

    public enum Status {
        PROGRESS,
        WAITING,
        STALLED
    }

    private long lastStamp = UNKNOWN;
    private int lastChange;

    public ProgressWatch(int startTick) {
        this.lastChange = startTick;
    }

    /**
     * @param stamp the task's progress stamp, or {@link #UNKNOWN} when it does not expose one
     * @param waitingTicks how long the task has legitimately waited, or a negative value when it is not waiting
     * @param waitingLimitTicks the task's own deadline for that wait, or zero for the outer stall limit
     * @param activeTick the runner's monotonic active-tick counter
     * @param stallTicks how many ticks without a new stamp count as a stall
     */
    public Status observe(long stamp, int waitingTicks, int waitingLimitTicks, int activeTick, int stallTicks) {
        if (waitingTicks >= 0) {
            if (waitingLimitTicks > 0 && waitingTicks > waitingLimitTicks) {
                return Status.STALLED;
            }
            return Status.WAITING;
        }
        if (stamp == UNKNOWN) {
            return Status.PROGRESS;
        }
        if (lastStamp == UNKNOWN || stamp != lastStamp) {
            lastStamp = stamp;
            lastChange = activeTick;
            return Status.PROGRESS;
        }
        return activeTick - lastChange >= Math.max(1, stallTicks) ? Status.STALLED : Status.PROGRESS;
    }

    public int ticksSinceChange(int activeTick) {
        return Math.max(0, activeTick - lastChange);
    }
}
