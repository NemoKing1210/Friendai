package dev.friendai.server.execution.tools;

import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskState;
import java.util.Optional;

/**
 * Restores one task's bounded checkpoint. A feature contributes it beside the task definition, keyed by a stable
 * checkpoint type, so adding a restorable task never edits the restore orchestration. Caller, creative-mode and
 * trading authority are checked by the restore coordinator before the decoded task is activated.
 */
@FunctionalInterface
public interface CheckpointRestorer {
    /** @return the resumed task, or empty for malformed or unavailable checkpoint data */
    Optional<Task> restore(TaskState state, TaskContext context);
}
