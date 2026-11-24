package dev.friendai.features.mining;

/**
 * The bound on felling attempts that give nothing. Consecutive empty attempts — not a lifetime count — are the
 * evidence that the trees around here cannot be felled for this companion; a productive tree in between proves the
 * companion can still work the forest, so its streak starts again. When the bound is reached the existing count
 * miner takes over rather than the companion walking to another tree.
 */
public final class FellAttempts {
    private FellAttempts() {}

    /** The streak after one finished tree attempt that added {@code yielded} logs. */
    public static int nextEmptyStreak(int emptyStreak, int yielded) {
        return yielded > 0 ? 0 : emptyStreak + 1;
    }

    /** Whether the count miner takes over instead of another tree attempt. */
    public static boolean fallBack(int trees, int emptyStreak, int maxTrees, int maxEmpty) {
        return trees >= maxTrees || emptyStreak >= maxEmpty;
    }
}
