package dev.friendai.server.execution.persistence;

import dev.friendai.execution.queue.QueueSave;
import dev.friendai.task.TaskState;

/** Typed in-memory form of one companion's durable queue and active task checkpoint. */
public record SavedExecution(int version, QueueSave.Decoded queue, TaskState activeTask) {
    public static final int CURRENT_VERSION = 1;

    public SavedExecution {
        if (version != CURRENT_VERSION) throw new IllegalArgumentException("unsupported execution format");
        if (queue == null) throw new IllegalArgumentException("queue state is required");
    }
}
