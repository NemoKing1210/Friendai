package dev.friendai.entity;

import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.phys.Vec3;

/**
 * Water locomotion with a player's conventions instead of a mob's. A mob's {@link net.minecraft.world.entity.ai.goal.FloatGoal}
 * bobs it on the surface with an upright body; the companion instead takes the swimming pose whenever a route carries
 * it through water deep enough to swim in, like a player holding the sprint key, and holds its depth at the surface
 * whenever nothing is driving it. The pose is synced, so the client plays the player's swim animation and lays the
 * body down from it.
 *
 * <p>Only the server ticks this; the navigator owns the depth and the direction while a path runs.
 */
public final class CompanionSwim {
    /** Depth of the body under water that a player needs before they swim rather than wade, in blocks. */
    private static final double SWIM_DEPTH = 0.9;
    /**
     * The depth of the water over the feet that an undriven body settles at: the level a route holds on the surface,
     * so the hand-over between a route and standing still does not move it.
     */
    private static final double FLOAT_DEPTH = 0.85;
    /** How hard a body that has sunk below {@link #FLOAT_DEPTH} is pushed back up, per block of excess depth. */
    private static final double FLOAT_GAIN = 0.5;
    /** The fastest the surface hold lifts a submerged body, and the fastest it lets a risen one sink, per tick. */
    private static final double FLOAT_RISE = 0.15;

    private static final double FLOAT_SINK = 0.05;

    private final CompanionEntity companion;

    CompanionSwim(CompanionEntity companion) {
        this.companion = companion;
    }

    void tick() {
        boolean inWater = companion.isInWater() && !companion.isPassenger();
        boolean swimming = inWater && companion.pathing() && deepEnough();
        if (swimming && companion.getPose() != Pose.SWIMMING) {
            companion.setPose(Pose.SWIMMING);
        } else if (!swimming && companion.getPose() == Pose.SWIMMING) {
            companion.setPose(Pose.STANDING);
        }
        if (inWater && !companion.pathing()) {
            // A player holds the jump key to stay at the surface; with no float goal nothing else would, and a
            // companion between two routes, or idle, would sink until its head went under and then be thrown back up.
            double excess = companion.getFluidHeight(FluidTags.WATER) - FLOAT_DEPTH;
            Vec3 velocity = companion.getDeltaMovement();
            double lift = Math.max(-FLOAT_SINK, Math.min(FLOAT_RISE, excess * FLOAT_GAIN));
            companion.setDeltaMovement(velocity.x, Math.max(velocity.y, lift), velocity.z);
        }
    }

    /**
     * Deep water is swum, shallow water is waded, like a player crossing a stream: the body stays upright there.
     * Water under the feet counts even when it only reaches a surface route's knees, which is what a lake surface is.
     */
    private boolean deepEnough() {
        if (companion.getFluidHeight(FluidTags.WATER) > SWIM_DEPTH) {
            return true;
        }
        return companion
                .level()
                .getFluidState(companion.blockPosition().below())
                .is(FluidTags.WATER);
    }
}
