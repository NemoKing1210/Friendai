package dev.friendai.execution.history;

import dev.friendai.execution.TaskDisposition;

/** A task history row with typed outcome and its stable protocol label. */
public record TaskRecord(int id, String toolName, TaskDisposition disposition, String summary) {
    public TaskRecord(int id, String toolName, String status, String summary) {
        this(id, toolName, TaskDisposition.fromHistoryStatus(status), summary);
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
