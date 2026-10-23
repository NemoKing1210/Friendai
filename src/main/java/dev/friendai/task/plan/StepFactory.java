package dev.friendai.task.plan;

import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;

/** Builds the task for one plan step when the step begins, so world and inventory facts are current. */
@FunctionalInterface
public interface StepFactory {
    /** @throws IllegalArgumentException with an LLM-readable message when the step cannot be built (unknown item, missing owner) */
    Task create(TaskContext c);
}
