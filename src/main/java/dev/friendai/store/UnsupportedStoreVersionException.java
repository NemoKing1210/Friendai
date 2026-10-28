package dev.friendai.store;

/** A newer data format must remain intact instead of being treated as corrupt. */
public final class UnsupportedStoreVersionException extends IllegalStateException {
    public UnsupportedStoreVersionException() {
        super("Unsupported store version.");
    }
}
