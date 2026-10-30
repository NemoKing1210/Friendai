package dev.friendai.entity;

import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.phys.Vec3;

/**
 * Water locomotion with a player's conventions instead of a mob's. A mob's {@link net.minecraft.world.entity.ai.goal.FloatGoal}
 * bobs it on the surface with an upright body; the companion instead takes the swimming pose whenever it moves through
 * water deep enough to swim in, and treads water to keep its head out while it stands still. The pose is synced, so the
 * client plays the player's swim animation and lays the body down from it.
 *
 * <p>Only the server ticks this; the navigator owns the depth and the direction while a path runs.
 */
public final class CompanionSwim {
    /** Below this horizontal speed the companion is not travelling, so it only floats where it is. */
    private static final double MOVING = 0.02;
    /** Depth of the body under water that a player needs before they swim rather than wade, in blocks. */
    private static final double SWIM_DEPTH = 0.9;
    /** The upward impulse a player's jump key gives in water, applied once per tick while treading water. */
    private static final double PADDLE = 0.04;

    private final CompanionEntity companion;

    CompanionSwim(CompanionEntity companion) {
        this.companion = companion;
    }

    void tick() {
        boolean inWater = companion.isInWater() && !companion.isPassenger();
        boolean swimming = inWater && (companion.pathing() || moving()) && deepEnough();
        if (swimming && companion.getPose() != Pose.SWIMMING) {
            companion.setPose(Pose.SWIMMING);
        } else if (!swimming && companion.getPose() == Pose.SWIMMING) {
            companion.setPose(Pose.STANDING);
        }
        if (inWater && !swimming && companion.isEyeInFluid(FluidTags.WATER)) {
            // A player holds the jump key to keep their head above water; with no float goal nothing else would, and
            // an idle companion left under the surface would drown.
            Vec3 velocity = companion.getDeltaMovement();
            companion.setDeltaMovement(velocity.x, velocity.y + PADDLE, velocity.z);
        }
    }

    /** Shallow water is waded, like a player crossing a stream: the body stays upright there. */
    private boolean deepEnough() {
        return companion.getFluidHeight(FluidTags.WATER) > SWIM_DEPTH;
    }

    private boolean moving() {
        Vec3 velocity = companion.getDeltaMovement();
        return velocity.horizontalDistanceSqr() > MOVING * MOVING;
    }
}
