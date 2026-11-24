package dev.friendai.server.execution.persistence;

import dev.friendai.execution.queue.QueueSave;
import dev.friendai.server.execution.RetryCheckpoints;
import dev.friendai.task.TaskState;
import java.util.Map;

/**
 * Typed in-memory form of one companion's durable queue, active task checkpoint, the progress a retry resumes and the
 * active budget the restored activation has already spent.
 */
public record SavedExecution(
        int version,
        QueueSave.Decoded queue,
        TaskState activeTask,
        boolean manualPause,
        Map<Long, RetryCheckpoints.Entry> retryProgress,
        int activeElapsedTicks,
        int activeTimeoutSec) {
    public static final int CURRENT_VERSION = 4;
    /** The format before the active clock was saved; such a document starts a fresh activation clock. */
    public static final int PREVIOUS_VERSION = 3;
    /** The earliest format that still loads: the queue and active checkpoint, without pause or retries. */
    public static final int LEGACY_VERSION = 1;
    /** The largest restored active budget a save may claim; a bigger number is a corrupt or hostile document. */
    public static final int MAX_ACTIVE_ELAPSED_TICKS = 1_000_000;
    /** The longest restored activation timeout a save may claim. */
    public static final int MAX_ACTIVE_TIMEOUT_SEC = 86_400;

    public SavedExecution {
        if (version < LEGACY_VERSION || version > CURRENT_VERSION) {
            throw new IllegalArgumentException("unsupported execution format");
        }
        if (queue == null) throw new IllegalArgumentException("queue state is required");
        retryProgress = retryProgress == null ? Map.of() : Map.copyOf(retryProgress);
        if (activeElapsedTicks < 0 || activeElapsedTicks > MAX_ACTIVE_ELAPSED_TICKS) {
            throw new IllegalArgumentException("active elapsed ticks out of range");
        }
        if (activeTimeoutSec < 0 || activeTimeoutSec > MAX_ACTIVE_TIMEOUT_SEC) {
            throw new IllegalArgumentException("active timeout out of range");
        }
    }

    /** A document from before the pause was saved: it cannot claim the owner paused the work. */
    public SavedExecution(int version, QueueSave.Decoded queue, TaskState activeTask) {
        this(version < LEGACY_VERSION ? LEGACY_VERSION : CURRENT_VERSION, queue, activeTask, false, Map.of(), 0, 0);
    }

    /** The same state at the current format version, carrying the owner's pause. */
    public SavedExecution(QueueSave.Decoded queue, TaskState activeTask, boolean manualPause) {
        this(CURRENT_VERSION, queue, activeTask, manualPause, Map.of(), 0, 0);
    }

    /** The same state at the current format version with the progress a failed attempt left for its next try. */
    public SavedExecution(
            QueueSave.Decoded queue,
            TaskState activeTask,
            boolean manualPause,
            Map<Long, RetryCheckpoints.Entry> retryProgress) {
        this(CURRENT_VERSION, queue, activeTask, manualPause, retryProgress, 0, 0);
    }

    /** The same state with the active budget the restored activation already spent. */
    public SavedExecution(
            QueueSave.Decoded queue,
            TaskState activeTask,
            boolean manualPause,
            Map<Long, RetryCheckpoints.Entry> retryProgress,
            int activeElapsedTicks,
            int activeTimeoutSec) {
        this(CURRENT_VERSION, queue, activeTask, manualPause, retryProgress, activeElapsedTicks, activeTimeoutSec);
    }
}
