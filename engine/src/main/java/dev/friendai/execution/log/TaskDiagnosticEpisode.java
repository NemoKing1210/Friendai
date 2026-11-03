package dev.friendai.execution.log;

import dev.friendai.task.ProgressWatch;

/**
 * Decides when an activation deserves one "no progress" snapshot: not once per tick, but once per
 * {@value #SAMPLE_INTERVAL_TICKS} active ticks without material progress. A moved progress stamp or a different blocker
 * starts a fresh episode immediately; a legitimate wait and an explicit hold never warn. Allocation-free on every
 * tick, so a runner can call it unconditionally.
 */
public final class TaskDiagnosticEpisode {
    public static final int SAMPLE_INTERVAL_TICKS = 200;

    private boolean started;
    private long stamp = ProgressWatch.UNKNOWN;
    private String blocker = "";
    private int lastSampleTick = Integer.MIN_VALUE;

    /**
     * @param activeTick the runner's monotonic active-tick counter
     * @param progressStamp the task's {@link dev.friendai.task.Task#progressStamp()}
     * @param waiting true when the task is legitimately waiting on the world or a guard
     * @param blocker the task's current blocker description, or null/blank when it reports none
     * @return true when one bounded snapshot is due
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
        if (materialProgress || blockerChanged) {
            stamp = progressStamp;
            this.blocker = currentBlocker;
            lastSampleTick = activeTick;
            return false;
        }
        if (waiting) return false;
        if (activeTick - lastSampleTick < SAMPLE_INTERVAL_TICKS) return false;
        lastSampleTick = activeTick;
        return true;
    }
}
