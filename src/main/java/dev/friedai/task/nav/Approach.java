package dev.friedai.task.nav;

import dev.friedai.entity.CompanionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Walks a companion until a block is within reach. */
public final class Approach {
    public enum State {
        MOVING,
        IN_REACH,
        UNREACHABLE
    }

    /** How close the companion's eyes must be to the block centre to act on it. */
    public static final double REACH = 4.0;

    private final BlockPos target;
    private final MoveHelper mover = new MoveHelper(1.0, 1.5);

    public Approach(BlockPos target) {
        this.target = target;
    }

    public State tick(CompanionEntity companion) {
        Vec3 center = Vec3.atCenterOf(target);
        if (companion.getEyePosition().distanceTo(center) <= REACH) {
            companion.getNavigation().stop();
            companion.getLookControl().setLookAt(center.x, center.y, center.z);
            return State.IN_REACH;
        }
        if (mover.tick(companion, center) == MoveHelper.State.STUCK) {
            return State.UNREACHABLE;
        }
        return State.MOVING;
    }

    public void stop(CompanionEntity companion) {
        companion.getNavigation().stop();
    }
}
