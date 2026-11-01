package dev.friendai.task;

/**
 * Decides which of a task's ticks deserve a {@link TaskProgress} event. Transition-driven by design: a phase change is
 * reported at once, a repeated phase never, and {@link TaskPhase#PROGRESS} again only when the task's own stamp moved
 * and at most once per {@link #PROGRESS_INTERVAL_TICKS} active ticks. Pure, and allocation-free unless an event is due,
 * so a runner can call it every tick.
 */
public final class TaskProgressThrottle {
    /** The shortest gap between two PROGRESS events of the same task. */
    public static final int PROGRESS_INTERVAL_TICKS = 100;

    private static final int NO_TASK = Integer.MIN_VALUE;

    private int taskId = NO_TASK;
    private TaskPhase phase;
    private long stamp = ProgressWatch.UNKNOWN;
    private int percent = -1;
    private int eventTick = Integer.MIN_VALUE;

    /**
     * Whether moving from {@code previous} to {@code next} is a change the owner and the model should hear about. A
     * null {@code previous} is the first observation of a task; a null {@code next} is not a phase at all.
     */
    public static boolean deservesEvent(TaskPhase previous, TaskPhase next) {
        return next != null && next != previous;
    }

    /** The phase of the last event this throttle let through, or null before the first one. */
    public TaskPhase phase() {
        return phase;
    }

    /** Forgets the current task; the next observation starts a fresh lifecycle even when it reuses the task id. */
    public void reset() {
        taskId = NO_TASK;
        phase = null;
        stamp = ProgressWatch.UNKNOWN;
        percent = -1;
        eventTick = Integer.MIN_VALUE;
    }

    /**
     * @param percent the task's measured completion in 0..100, or -1 when it cannot measure itself
     * @param detail one concise English line for the owner and the model
     * @param stamp the task's {@link Task#progressStamp()}, or {@link ProgressWatch#UNKNOWN} when it has none
     * @param activeTick the runner's monotonic active-tick counter
     * @return the event to publish, or null when this observation must stay silent
     */
    public TaskProgress observe(
            int id, String tool, TaskPhase next, int percent, String detail, long stamp, int activeTick) {
        if (next == null) return null;
        if (id != taskId) {
            reset();
            taskId = id;
        }
        // Clamp once, so a stored milestone and the published one agree about what counts as movement.
        percent = Math.max(-1, Math.min(100, percent));
        boolean due = deservesEvent(phase, next);
        if (!due && next == TaskPhase.PROGRESS) {
            due = progressed(percent, stamp) && activeTick - eventTick >= PROGRESS_INTERVAL_TICKS;
        }
        if (!due) return null;
        phase = next;
        this.stamp = stamp;
        this.percent = percent;
        eventTick = activeTick;
        return new TaskProgress(id, tool, next, percent, detail);
    }

    /** A phase is only the label of a milestone; a repeated PROGRESS also needs the task's stamp or percent to move. */
    private boolean progressed(int percent, long stamp) {
        boolean stampMoved = stamp != ProgressWatch.UNKNOWN && stamp != this.stamp;
        boolean percentMoved = percent >= 0 && percent != this.percent;
        return stampMoved || percentMoved;
    }
}
