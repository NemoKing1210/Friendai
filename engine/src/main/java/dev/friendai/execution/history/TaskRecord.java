package dev.friendai.execution.history;

import dev.friendai.execution.TaskDisposition;
import dev.friendai.execution.TaskWorkResult;
import java.util.Optional;

/** A task history row with typed outcome and its stable protocol label. */
public record TaskRecord(int id, String toolName, TaskDisposition disposition, String summary) {
    public TaskRecord(int id, String toolName, String status, String summary) {
        this(id, toolName, TaskDisposition.fromHistoryStatus(status), summary);
    }

    /**
     * A settled job row that keeps what its last activation measured, appended to the existing bounded summary so the
     * panel can name the quantities without a new protocol field.
     */
    public TaskRecord(
            int id, String toolName, TaskDisposition disposition, String summary, Optional<TaskWorkResult> workResult) {
        this(
                id,
                toolName,
                disposition,
                workResult == null || workResult.isEmpty()
                        ? summary
                        : (summary == null ? "" : summary) + " "
                                + workResult.get().compact());
    }

    public TaskRecord {
        if (disposition == null) disposition = TaskDisposition.FAILED;
        toolName = toolName == null ? "" : toolName;
        summary = summary == null ? "" : summary;
    }

    /** Kept as a string for the existing companion snapshot protocol. */
    public String status() {
        return disposition.historyStatus();
    }
}
