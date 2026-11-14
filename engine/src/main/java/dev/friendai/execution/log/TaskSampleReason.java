package dev.friendai.execution.log;

/**
 * Why one task state sample was published. The token is what a durable record carries, so a reader never has to infer
 * whether an unchanged sample was an ordinary heartbeat, an intentional wait or the progress watchdog giving up.
 */
public enum TaskSampleReason {
    /** A phase observation: the task entered a new phase or its stamp moved. */
    RUNNING("running"),
    /** The task declared a legitimate wait through {@link dev.friendai.task.Task#waitingTicks()}. */
    WAITING("waiting"),
    /** An ordinary periodic snapshot of work that has not moved yet; not a verdict. */
    SAMPLE("sample"),
    /** The progress watchdog found no progress for its whole window and is stopping the task. */
    STALLED("stalled");

    private final String wire;

    TaskSampleReason(String wire) {
        this.wire = wire;
    }

    /** The bounded token written to a diagnostic record. */
    public String wire() {
        return wire;
    }
}
