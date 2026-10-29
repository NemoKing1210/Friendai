package dev.friendai.foundation.persistence;

/**
 * A configuration file is damaged and its backup could not be created, so the store keeps the original bytes and
 * refuses every write until a later load can back it up. The message never contains configuration values.
 */
public final class ProtectedConfigException extends RuntimeException {
    public ProtectedConfigException() {
        super("configuration is read-only after a failed backup");
    }
}
