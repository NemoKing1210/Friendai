package dev.friendai.features.storage;

/** Bounds and freshness policy for one owner's observed storage containers. */
public record StorageLimits(int maxContainers, int maxKindsPerContainer, int staleAfterTicks) {
    public static final StorageLimits DEFAULTS = new StorageLimits(256, 64, 24_000);

    public StorageLimits {
        if (maxContainers < 16 || maxContainers > 1024) {
            throw new IllegalArgumentException("maxContainers must be 16..1024");
        }
        if (maxKindsPerContainer < 8 || maxKindsPerContainer > 256) {
            throw new IllegalArgumentException("maxKindsPerContainer must be 8..256");
        }
        if (staleAfterTicks < 600 || staleAfterTicks > 240_000) {
            throw new IllegalArgumentException("staleAfterTicks must be 600..240000");
        }
    }
}
