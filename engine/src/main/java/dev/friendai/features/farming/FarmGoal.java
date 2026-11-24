package dev.friendai.features.farming;

/**
 * What a pass should do. {@code crop} is a seed item id to sow, or null to choose; {@code seedReserve} seeds of each
 * kind are never sown (replanting may use them). {@code harvestCrop} turns the pass into a harvest run for one crop
 * block id, capped at {@code harvestLimit} picked blocks (0 = no cap): it harvests and replants those cells only and
 * leaves sowing, tilling and bone meal out of the plan.
 */
public record FarmGoal(
        String crop,
        boolean till,
        boolean useBoneMeal,
        int seedReserve,
        int maxSteps,
        String harvestCrop,
        int harvestLimit) {
    public static final int DEFAULT_MAX_STEPS = 512;

    public FarmGoal(String crop, boolean till, boolean useBoneMeal, int seedReserve, int maxSteps) {
        this(crop, till, useBoneMeal, seedReserve, maxSteps, null, 0);
    }

    public FarmGoal {
        if (seedReserve < 0) {
            throw new IllegalArgumentException("seedReserve must not be negative: " + seedReserve);
        }
        if (maxSteps < 1) {
            throw new IllegalArgumentException("maxSteps must be at least 1: " + maxSteps);
        }
        if (harvestLimit < 0) {
            throw new IllegalArgumentException("harvestLimit must not be negative: " + harvestLimit);
        }
        if (harvestCrop != null && harvestCrop.isBlank()) {
            throw new IllegalArgumentException("harvestCrop must not be blank");
        }
    }

    public static FarmGoal defaults() {
        return new FarmGoal(null, false, false, 0, DEFAULT_MAX_STEPS);
    }

    /** Whether this pass picks one chosen crop instead of working the whole field. */
    public boolean harvestingOnly() {
        return harvestCrop != null;
    }
}
