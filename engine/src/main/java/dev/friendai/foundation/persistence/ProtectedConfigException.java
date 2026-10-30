package dev.friendai.foundation.persistence;

/**
 * A configuration could not be read, recovered or upgraded safely. The store keeps the original bytes and
 * refuses every write until a later load succeeds. The message never contains configuration values.
 */
public final class ProtectedConfigException extends RuntimeException {
    public ProtectedConfigException() {
        super("configuration is read-only after a failed read, recovery or upgrade");
    }
}
