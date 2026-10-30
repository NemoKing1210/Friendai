package dev.friendai.features.survival.selfcare;

import java.util.Optional;

/**
 * The one self-care decision, in a fixed order: a landing, then a fall that cannot be caught, then the effect that
 * keeps the body alive where it stands, then critical health, then air, then a debuff worth a bucket, then the owner.
 *
 * <p>Exactly one item per emergency: the first matching rule wins and the caller never looks further. Everything is
 * a comparison over {@link SelfCareFacts}, so both the action and the abstention are testable.
 */
public final class SelfCarePlanner {
    /** Below this fraction of health the companion heals on its own, in or out of a fight. */
    public static final float CRITICAL_HEALTH = 0.30f;

    /** Below this fraction the plain golden apple is not enough and the enchanted one is worth spending. */
    public static final float LETHAL_HEALTH = 0.15f;

    /** Fall distance from which vanilla hurts; a shorter drop is not an emergency. */
    public static final float FALL_HURT_BLOCKS = 3.0f;

    /**
     * How far below the feet a water source can be poured: the fake player's eye sits 1.62 above the feet and the
     * bucket's own raycast reaches 4.5 from the eye, so a solid face about 2.8 blocks down is the last one it can hit.
     * Half a block of margin keeps the pour away from the edge of the reach.
     */
    public static final double WATER_REACH_BLOCKS = 2.5;

    /** Air left in ticks below which a water-breathing potion is worth drinking (the bar is 300). */
    public static final int LOW_AIR_TICKS = 150;

    /** How close the owner must stand for a splash potion to be worth throwing. */
    public static final double OWNER_REACH_BLOCKS = 4.0;

    /** Owner health fraction at or below which the companion spends a splash potion on them. */
    public static final float OWNER_HURT = 0.5f;

    private static final String FIRE_RESISTANCE = "minecraft:fire_resistance";
    private static final String WATER_BREATHING = "minecraft:water_breathing";
    private static final String SLOW_FALLING = "minecraft:slow_falling";

    private SelfCarePlanner() {}

    public static Optional<SelfCareDecision> decide(SelfCareFacts f) {
        // The fire reflex owns a burning body; two reflexes spending one item would fight over the same hands.
        if (f.onFire()) {
            return Optional.empty();
        }
        if (f.fallingToHurt()) {
            Optional<SelfCareDecision> landing = landing(f);
            if (landing.isPresent()) {
                return landing;
            }
        }
        if (f.inLava() && f.stock().fireResistancePotions() > 0 && !f.hasEffect(FIRE_RESISTANCE)) {
            return Optional.of(new SelfCareDecision(SelfCareKind.FIRE_RESISTANCE, SelfCareItem.POTION_FIRE_RESISTANCE));
        }
        if (f.healthFraction() <= CRITICAL_HEALTH) {
            Optional<SelfCareDecision> heal = heal(f);
            if (heal.isPresent()) {
                return heal;
            }
        }
        if (f.submerged()
                && !f.hasEffect(WATER_BREATHING)
                && f.airSupplyTicks() <= LOW_AIR_TICKS
                && f.stock().waterBreathingPotions() > 0) {
            return Optional.of(new SelfCareDecision(SelfCareKind.WATER_BREATHING, SelfCareItem.POTION_WATER_BREATHING));
        }
        if (f.stock().milkBuckets() > 0 && worthMilk(f)) {
            return Optional.of(new SelfCareDecision(SelfCareKind.MILK, SelfCareItem.MILK_BUCKET));
        }
        if (f.ownerPresent()
                && f.ownerDistance() <= OWNER_REACH_BLOCKS
                && f.ownerHealthFraction() <= OWNER_HURT
                && !f.ownerThreatNear()
                && f.stock().splashHealingPotions() > 0) {
            return Optional.of(
                    new SelfCareDecision(SelfCareKind.SPLASH_HEAL_OWNER, SelfCareItem.SPLASH_POTION_HEALING));
        }
        return Optional.empty();
    }

    /** A water bucket on the face below, or slow falling when no bucket can do it. */
    private static Optional<SelfCareDecision> landing(SelfCareFacts f) {
        if (f.stock().waterBuckets() > 0
                && f.supportDistance() >= 0
                && f.supportDistance() <= WATER_REACH_BLOCKS
                && f.supportPlaceable()) {
            return Optional.of(new SelfCareDecision(SelfCareKind.WATER_DROP, SelfCareItem.WATER_BUCKET));
        }
        if (f.stock().waterBuckets() == 0 && f.stock().slowFallingPotions() > 0 && !f.hasEffect(SLOW_FALLING)) {
            return Optional.of(new SelfCareDecision(SelfCareKind.SLOW_FALLING, SelfCareItem.POTION_SLOW_FALLING));
        }
        return Optional.empty();
    }

    /** The apple first, then a potion; the enchanted apple only when the plain one is gone or the situation is lethal. */
    private static Optional<SelfCareDecision> heal(SelfCareFacts f) {
        boolean lethal = f.healthFraction() <= LETHAL_HEALTH;
        if (lethal && f.stock().enchantedGoldenApples() > 0) {
            return Optional.of(
                    new SelfCareDecision(SelfCareKind.ENCHANTED_GOLDEN_APPLE, SelfCareItem.ENCHANTED_GOLDEN_APPLE));
        }
        if (f.stock().goldenApples() > 0) {
            return Optional.of(new SelfCareDecision(SelfCareKind.GOLDEN_APPLE, SelfCareItem.GOLDEN_APPLE));
        }
        if (f.stock().enchantedGoldenApples() > 0) {
            return Optional.of(
                    new SelfCareDecision(SelfCareKind.ENCHANTED_GOLDEN_APPLE, SelfCareItem.ENCHANTED_GOLDEN_APPLE));
        }
        if (f.stock().healingPotions() > 0) {
            return Optional.of(new SelfCareDecision(SelfCareKind.HEALING_POTION, SelfCareItem.POTION_HEALING));
        }
        if (f.stock().regenerationPotions() > 0) {
            return Optional.of(
                    new SelfCareDecision(SelfCareKind.REGENERATION_POTION, SelfCareItem.POTION_REGENERATION));
        }
        return Optional.empty();
    }

    private static boolean worthMilk(SelfCareFacts f) {
        if (SelfCareEffects.carrying(
                f.effects(), f.inLava(), f.submerged(), f.fallingToHurt(), f.healthFraction() <= CRITICAL_HEALTH)) {
            return false;
        }
        return SelfCareEffects.worthClearing(f.effects(), f.working());
    }
}
