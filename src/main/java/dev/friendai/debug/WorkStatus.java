package dev.friendai.debug;

/** Progress state for one cell in a task-owned work set. Keep ordinals stable after encoding. */
public enum WorkStatus {
    DONE,
    NEXT,
    PENDING,
    SKIPPED
}
