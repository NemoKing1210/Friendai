package dev.friendai.execution.queue;

public enum JobState {
    PENDING,
    RUNNING,
    DONE,
    FAILED,
    SKIPPED,
    CANCELLED
}
