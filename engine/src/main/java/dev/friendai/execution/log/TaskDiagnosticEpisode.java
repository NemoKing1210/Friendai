package dev.friendai.execution.log;

import dev.friendai.task.ProgressWatch;

/**
 * Decides when an activation deserves one state sample: not once per tick, but once per
 * {@value #SAMPLE_INTERVAL_TICKS} active ticks without material progress. A moved progress stamp or a different blocker
 * starts a fresh episode immediately; waiting samples use the same cadence; explicit holds are not observed. Allocation-free on every
 * tick, so a runner can call it unconditionally.
 */
public final class TaskDiagnosticEpisode {
    public static final int SAMPLE_INTERVAL_TICKS = 200;

    private boolean started;
    private long stamp = ProgressWatch.UNKNOWN;
    private String blocker = "";
    private int lastSampleTick = Integer.MIN_VALUE;
    private int firstProgressTick = -1;
    private int lastProgressTick = Integer.MIN_VALUE;
    private int longestGap;

    /**
     * @param activeTick the runner's monotonic active-tick counter
     * @param progressStamp the task's {@link dev.friendai.task.Task#progressStamp()}
     * @param waiting true when the task is legitimately waiting on the world or a guard
     * @param blocker the task's current blocker description, or null/blank when it reports none
     * @return true when one bounded state sample is due
     */
    public boolean sample(int activeTick, long progressStamp, boolean waiting, String blocker) {
        String currentBlocker = blocker == null ? "" : blocker;
        if (!started) {
            started = true;
            stamp = progressStamp;
            this.blocker = currentBlocker;
            lastSampleTick = activeTick;
            return false;
        }
        boolean materialProgress = progressStamp != ProgressWatch.UNKNOWN && progressStamp != stamp;
        boolean blockerChanged = !currentBlocker.equals(this.blocker);
        if (materialProgress) {
            if (firstProgressTick < 0) {
                firstProgressTick = activeTick;
            } else {
                longestGap = Math.max(longestGap, activeTick - lastProgressTick);
            }
            lastProgressTick = activeTick;
        }
        if (materialProgress || blockerChanged) {
            stamp = progressStamp;
            this.blocker = currentBlocker;
            lastSampleTick = activeTick;
            return false;
        }
        if (activeTick - lastSampleTick < SAMPLE_INTERVAL_TICKS) return false;
        lastSampleTick = activeTick;
        return true;
    }

    /**
     * The active tick of the first material progress, or {@code -1} when the task never moved its stamp. Measured from
     * the runner's activation tick by the caller, so {@code task start → first verified work} is one subtraction.
     */
    public int firstProgressTick() {
        return firstProgressTick;
    }

    /**
     * The longest interval without material progress up to {@code activeTick}, including the tail after the last
     * change and the whole activation when nothing ever moved. A declared wait still counts as time without progress:
     * the typed sample reason, not this measurement, says whether the wait was intentional.
     */
    public int longestNoProgressTicks(int activeTick) {
        int tail = lastProgressTick == Integer.MIN_VALUE ? activeTick : activeTick - lastProgressTick;
        return Math.max(longestGap, Math.max(0, tail));
    }
}
