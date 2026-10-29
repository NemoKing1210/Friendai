package dev.friendai.foundation.persistence;

/** Safe persistence diagnostics: never include paths, data or exception messages. */
public enum StoreFailureKind {
    QUEUE_FULL,
    READ_FAILED,
    BACKUP_FAILED,
    WRITE_FAILED,
    SHUTDOWN_TIMEOUT
}
