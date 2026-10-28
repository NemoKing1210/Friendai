package dev.friendai.features.combat;

/**
 * What a candidate splash potion would do at its predicted impact, evaluated without the world.
 * The adapter fills these values from the real stack, target and nearby entities.
 */
public record PotionFacts(
        boolean harmful,
        boolean beneficial,
        boolean mixedEffects,
        int shortestDurationTicks,
        int affectedProtected,
        int affectedEnemies,
        boolean impactSafe,
        boolean cooldownReady,
        int budgetLeft) {
    /** The minimum duration that makes a splash worth spending. */
    public static final int MIN_USEFUL_DURATION = 20;
}
