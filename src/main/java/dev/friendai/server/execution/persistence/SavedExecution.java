package dev.friendai.server.execution.persistence;

import dev.friendai.execution.queue.QueueSave;
import dev.friendai.task.TaskState;

/** Typed in-memory form of one companion's durable queue and active task checkpoint. */
public record SavedExecution(int version, QueueSave.Decoded queue, TaskState activeTask, boolean manualPause) {
    public static final int CURRENT_VERSION = 2;
    /** The format before the owner's pause was saved; such a document means "not paused". */
    public static final int LEGACY_VERSION = 1;

    public SavedExecution {
        if (version != CURRENT_VERSION && version != LEGACY_VERSION) {
            throw new IllegalArgumentException("unsupported execution format");
        }
        if (queue == null) throw new IllegalArgumentException("queue state is required");
    }

    /** A document from before the pause was saved: it cannot claim the owner paused the work. */
    public SavedExecution(int version, QueueSave.Decoded queue, TaskState activeTask) {
        this(version == LEGACY_VERSION ? LEGACY_VERSION : CURRENT_VERSION, queue, activeTask, false);
    }

    /** The same state at the current format version, carrying the owner's pause. */
    public SavedExecution(QueueSave.Decoded queue, TaskState activeTask, boolean manualPause) {
        this(CURRENT_VERSION, queue, activeTask, manualPause);
    }
}
