package dev.friendai.features.storage;

/** Identifies one observed storage container by dimension and block position. */
public record StorageKey(String dimension, int x, int y, int z) {
    public StorageKey {
        if (dimension == null || dimension.isBlank()) {
            throw new IllegalArgumentException("dimension is required");
        }
    }
}
