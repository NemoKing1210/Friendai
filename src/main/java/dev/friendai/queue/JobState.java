package dev.friendai.queue;

public enum JobState {
    PENDING,
    RUNNING,
    DONE,
    FAILED,
    SKIPPED,
    CANCELLED
}
