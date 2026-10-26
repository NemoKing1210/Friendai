package dev.friendai.farming;

/**
 * What a pass should do. {@code crop} is a seed item id to sow, or null to choose; {@code seedReserve} seeds of each
 * kind are never sown (replanting may use them).
 */
public record FarmGoal(String crop, boolean till, boolean useBoneMeal, int seedReserve, int maxSteps) {
    public static final int DEFAULT_MAX_STEPS = 512;

    public FarmGoal {
        if (seedReserve < 0) {
            throw new IllegalArgumentException("seedReserve must not be negative: " + seedReserve);
        }
        if (maxSteps < 1) {
            throw new IllegalArgumentException("maxSteps must be at least 1: " + maxSteps);
        }
    }

    public static FarmGoal defaults() {
        return new FarmGoal(null, false, false, 0, DEFAULT_MAX_STEPS);
    }
}
