package dev.friendai.task.plan;

import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;

/** Builds the task for one plan step when the step begins, so world and inventory facts are current. */
@FunctionalInterface
public interface StepFactory {
    /** @throws IllegalArgumentException with an LLM-readable message when the step cannot be built (unknown item, missing owner) */
    Task create(TaskContext c);

    /**
     * Rechecked immediately before activation, so a job queued under an access level that has since been revoked never
     * starts. A factory registered from a stable, already-authorized source may keep the default.
     */
    default boolean authorized(TaskContext c) {
        return true;
    }
}
