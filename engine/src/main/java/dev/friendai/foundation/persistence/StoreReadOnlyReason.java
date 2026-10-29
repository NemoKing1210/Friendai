package dev.friendai.foundation.persistence;

/** Why a loaded owner cannot replace its original file. */
public enum StoreReadOnlyReason {
    CORRUPT,
    UNSUPPORTED,
    OVERSIZED,
    BACKUP_FAILED,
    READ_FAILED
}
