package dev.friendai.features.survival.selfcare;

import java.util.List;

/**
 * The self-care decision's inputs, filled by the game layer. No Minecraft types: the falling support, the effects
 * and the stock are facts, and the planner only compares them.
 *
 * @param supportDistance how many blocks of air sit between the feet and the top of the first solid cell below, or
 *     {@code -1} when nothing solid is within reach
 * @param supportPlaceable whether that support is reachable and the cell above it may hold a water source
 * @param working a task is running, so slowness and mining fatigue cost something
 */
public record SelfCareFacts(
        float healthFraction,
        boolean onGround,
        boolean inWater,
        boolean submerged,
        boolean inLava,
        boolean onFire,
        float fallDistance,
        double supportDistance,
        boolean supportPlaceable,
        int airSupplyTicks,
        boolean working,
        boolean ownerPresent,
        double ownerDistance,
        float ownerHealthFraction,
        boolean ownerThreatNear,
        List<SelfCareEffect> effects,
        SelfCareStock stock) {

    public boolean hasEffect(String id) {
        return SelfCareEffects.has(effects, id);
    }

    /** Airborne with a fall long enough to hurt, over something other than water or lava. */
    public boolean fallingToHurt() {
        return !onGround && !inWater && !inLava && fallDistance >= SelfCarePlanner.FALL_HURT_BLOCKS;
    }
}
