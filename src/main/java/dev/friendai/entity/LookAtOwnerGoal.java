package dev.friendai.entity;

import dev.friendai.features.combat.minecraft.CombatController;
import java.util.EnumSet;
import java.util.Optional;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.goal.Goal;

/**
 * Makes the companion glance at its owner while it is idle beside them.
 *
 * <p>A working companion has to keep its eyes on the job. The vanilla {@code LookAtPlayerGoal} that used to be
 * registered here re-targeted the nearest player from inside the goal selector, which runs before the task runner
 * and before the look control applies its rotation: every look target a task set (the block being mined, the
 * furnace, the placement spot) was overwritten, so the companion swivelled away from its work to stare at whoever
 * stood nearby. This goal declines while a real task or a fight is running, which leaves the look to the task.
 */
public final class LookAtOwnerGoal extends Goal {
    /** How far away the owner may stand and still be noticed. */
    private static final float LOOK_DISTANCE = 8.0f;
    /** Chance per tick to start a glance, the same rate as the vanilla goal this replaced. */
    private static final float PROBABILITY = 0.02f;

    private static final int MIN_GLANCE_TICKS = 40;
    private static final int EXTRA_GLANCE_TICKS = 40;
    /** An owner further than this from the body's heading makes the head sit at the cone, so the body follows. */
    private static final float BODY_TURN_CONE = 60.0f;
    /** How many glanced ticks outside the cone before the body turns too instead of a permanent head twist. */
    private static final int BODY_TURN_DELAY = 8;

    private final CompanionEntity companion;
    private ServerPlayer owner;
    private int glanceTicks;
    private int outsideConeTicks;

    public LookAtOwnerGoal(CompanionEntity companion) {
        this.companion = companion;
        setFlags(EnumSet.of(Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!canGlance()) {
            return false;
        }
        if (companion.getRandom().nextFloat() >= PROBABILITY) {
            return false;
        }
        Optional<ServerPlayer> candidate = companion
                .owner()
                .filter(player -> player.level() == companion.level())
                .filter(player -> companion.distanceToSqr(player) <= (double) LOOK_DISTANCE * LOOK_DISTANCE)
                .filter(player -> companion.getSensing().hasLineOfSight(player));
        owner = candidate.orElse(null);
        return owner != null;
    }

    @Override
    public boolean canContinueToUse() {
        return glanceTicks > 0
                && canGlance()
                && owner != null
                && owner.isAlive()
                && owner.level() == companion.level()
                && companion.distanceToSqr(owner) <= (double) LOOK_DISTANCE * LOOK_DISTANCE;
    }

    @Override
    public void start() {
        glanceTicks = adjustedTickDelay(MIN_GLANCE_TICKS + companion.getRandom().nextInt(EXTRA_GLANCE_TICKS));
        outsideConeTicks = 0;
    }

    @Override
    public void tick() {
        glanceTicks--;
        if (owner == null) {
            return;
        }
        companion.getLookControl().setLookAt(owner.getX(), owner.getEyeY(), owner.getZ());
        float yawToOwner = dev.friendai.features.navigation.movement.Facing.yawToward(
                owner.getX() - companion.getX(), owner.getZ() - companion.getZ());
        if (dev.friendai.features.navigation.movement.Facing.difference(companion.getYRot(), yawToOwner)
                > BODY_TURN_CONE) {
            if (++outsideConeTicks > BODY_TURN_DELAY) {
                companion.facing().lookAt(owner, 2);
            }
        } else {
            outsideConeTicks = 0;
        }
    }

    @Override
    public void stop() {
        owner = null;
        outsideConeTicks = 0;
    }

    /** True when the companion has nothing else to attend to: no task beyond the default follow, and no fight. */
    private boolean canGlance() {
        return companion.body().idleLook()
                && !companion.body().looking()
                && companion.idle()
                && companion.combatController().state() == CombatController.State.IDLE;
    }
}
