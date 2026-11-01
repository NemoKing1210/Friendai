package dev.friendai.task;

/**
 * Where a task stands in its life. The phases are deliberately few: the owner and the model need to tell working from
 * blocked and done, not every internal step a task takes. Pure and English, like the rest of the task vocabulary.
 */
public enum TaskPhase {
    /** The runner accepted the task and it has not reported any work yet. */
    STARTED,
    /** The task is actively working; a repeated report of this phase needs fresh evidence. */
    PROGRESS,
    /** The task is alive but blocked on the world or a guard until its own deadline. */
    WAITING,
    /** The task failed and was handed back to the queue for another attempt. */
    RETRYING,
    /** The task reached a terminal status: success, failure, timeout, crash or a clean stop. */
    FINISHED;

    /** Whether this phase ends the task's lifecycle. */
    public boolean terminal() {
        return this == FINISHED;
    }
}
