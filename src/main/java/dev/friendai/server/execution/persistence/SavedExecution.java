package dev.friendai.server.execution.persistence;

import dev.friendai.execution.queue.QueueSave;
import dev.friendai.server.execution.RetryCheckpoints;
import dev.friendai.task.TaskState;
import java.util.Map;

/**
 * Typed in-memory form of one companion's durable queue, active task checkpoint and the progress a retry resumes.
 */
public record SavedExecution(
        int version,
        QueueSave.Decoded queue,
        TaskState activeTask,
        boolean manualPause,
        Map<Long, RetryCheckpoints.Entry> retryProgress) {
    public static final int CURRENT_VERSION = 3;
    /** The format before the retry progress was saved; such a document means "nothing to resume". */
    public static final int PREVIOUS_VERSION = 2;
    /** The format before the owner's pause was saved; such a document means "not paused". */
    public static final int LEGACY_VERSION = 1;

    public SavedExecution {
        if (version != CURRENT_VERSION && version != PREVIOUS_VERSION && version != LEGACY_VERSION) {
            throw new IllegalArgumentException("unsupported execution format");
        }
        if (queue == null) throw new IllegalArgumentException("queue state is required");
        retryProgress = retryProgress == null ? Map.of() : Map.copyOf(retryProgress);
    }

    /** A document from before the pause was saved: it cannot claim the owner paused the work. */
    public SavedExecution(int version, QueueSave.Decoded queue, TaskState activeTask) {
        this(version == LEGACY_VERSION ? LEGACY_VERSION : CURRENT_VERSION, queue, activeTask, false, Map.of());
    }

    /** The same state at the current format version, carrying the owner's pause. */
    public SavedExecution(QueueSave.Decoded queue, TaskState activeTask, boolean manualPause) {
        this(CURRENT_VERSION, queue, activeTask, manualPause, Map.of());
    }

    /** The same state at the current format version with the progress a failed attempt left for its next try. */
    public SavedExecution(
            QueueSave.Decoded queue,
            TaskState activeTask,
            boolean manualPause,
            Map<Long, RetryCheckpoints.Entry> retryProgress) {
        this(CURRENT_VERSION, queue, activeTask, manualPause, retryProgress);
    }
}
