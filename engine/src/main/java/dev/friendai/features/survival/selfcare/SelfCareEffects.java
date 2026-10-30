package dev.friendai.features.survival.selfcare;

import java.util.List;

/**
 * When a milk bucket is worth its one item. The rules are a pure comparison so both directions can be pinned:
 * a harmful effect that still has time to hurt is worth clearing, unless a beneficial effect is what keeps the
 * companion alive right now.
 */
public final class SelfCareEffects {
    /** An effect with less than this left is nearly over: not worth a milk bucket. */
    public static final int MIN_WORTH_CLEARING_TICKS = 60;

    private static final String SLOWNESS = "minecraft:slowness";
    private static final String MINING_FATIGUE = "minecraft:mining_fatigue";
    private static final String FIRE_RESISTANCE = "minecraft:fire_resistance";
    private static final String WATER_BREATHING = "minecraft:water_breathing";
    private static final String SLOW_FALLING = "minecraft:slow_falling";
    private static final String REGENERATION = "minecraft:regeneration";
    private static final String ABSORPTION = "minecraft:absorption";

    private SelfCareEffects() {}

    public static boolean has(List<SelfCareEffect> effects, String id) {
        for (SelfCareEffect effect : effects) {
            if (effect.id().equals(id)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A harmful effect still worth a bucket: enough time left to hurt, and either the current job is slowed by it
     * or it hurts whatever the companion is doing.
     */
    public static boolean worthClearing(SelfCareEffect effect, boolean working) {
        if (!effect.harmful() || effect.durationTicks() < MIN_WORTH_CLEARING_TICKS) {
            return false;
        }
        return working || !(SLOWNESS.equals(effect.id()) || MINING_FATIGUE.equals(effect.id()));
    }

    public static boolean worthClearing(List<SelfCareEffect> effects, boolean working) {
        for (SelfCareEffect effect : effects) {
            if (worthClearing(effect, working)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A beneficial effect the situation depends on: milk clears it too, so it must not be drunk while one of these
     * is what keeps the companion alive.
     */
    public static boolean carrying(
            List<SelfCareEffect> effects, boolean inLava, boolean submerged, boolean falling, boolean critical) {
        return inLava && has(effects, FIRE_RESISTANCE)
                || submerged && has(effects, WATER_BREATHING)
                || falling && has(effects, SLOW_FALLING)
                || critical && (has(effects, REGENERATION) || has(effects, ABSORPTION));
    }
}
