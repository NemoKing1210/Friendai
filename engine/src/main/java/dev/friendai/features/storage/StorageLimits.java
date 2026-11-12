package dev.friendai.features.storage;

/** Bounds and freshness policy for one owner's observed storage containers. */
public record StorageLimits(int maxContainers, int maxKindsPerContainer, int staleAfterTicks) {
    public static final StorageLimits DEFAULTS = new StorageLimits(256, 64, 24_000);

    /**
     * How long after the companion opened a container it still sees what happens inside; the knowledge feature's
     * {@code ContainerFreshness.LIVE_TICKS}, repeated here because storage does not depend on knowledge.
     */
    public static final long LIVE_TICKS = 6_000;

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
