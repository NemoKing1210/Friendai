package dev.friendai.task.plan;

import dev.friendai.queue.QueuedStep;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;

/** Creates one freshly authorized leaf task from a stored action node. */
@FunctionalInterface
public interface PlanStepFactory {
    Task create(TaskContext context, QueuedStep step);

    /** Re-evaluated immediately before each emitted action, including after reload. */
    default boolean authorized(TaskContext context, QueuedStep step) { return true; }
}
