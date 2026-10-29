package dev.friendai.foundation.persistence;

/** Immutable status; nullable diagnostics mean no failure or read-only restriction. */
public record StoreWriteStatus(
        long currentRevision,
        long savedRevision,
        boolean pending,
        StoreFailureKind failure,
        StoreReadOnlyReason readOnlyReason) {
    public boolean dirty() {
        return currentRevision != savedRevision;
    }
}
