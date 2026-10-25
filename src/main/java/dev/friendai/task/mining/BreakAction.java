package dev.friendai.task.mining;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.task.TaskContext;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Breaks one block over time: equips the best tool, swings, shows the crack overlay and destroys the block. The drop
 * falls to the ground the way a player's does, so collecting it is the caller's job. The caller decides what each
 * returned state means for its own task.
 */
public final class BreakAction {
    public enum State { WORKING, BROKEN, GONE }

    private static final int SWING_INTERVAL = 5;

    private final BlockPos pos;
    private int breakTicks;
    private int breakNeeded;

    public BreakAction(BlockPos pos) {
        this.pos = pos;
    }

    public BlockPos pos() {
        return pos;
    }

    public State tick(TaskContext c, Predicate<BlockState> stillValid) {
        var companion = c.companion();
        ServerLevel level = c.level();
        BlockState state = level.getBlockState(pos);
        if (!stillValid.test(state)) {
            level.destroyBlockProgress(companion.getId(), pos, -1);
            return State.GONE;
        }
        if (breakNeeded == 0) {
            ToolSelector.equipBest(companion, state);
            var tool = companion.getMainHandItem();
            boolean correct = !state.requiresCorrectToolForDrops() || tool.isCorrectToolForDrops(state);
            breakNeeded = BreakProgress.ticksToBreak(state.getDestroySpeed(level, pos), tool.getDestroySpeed(state), correct);
        }
        Vec3 center = Vec3.atCenterOf(pos);
        companion.getLookControl().setLookAt(center.x, center.y, center.z);
        if (breakTicks % SWING_INTERVAL == 0) {
            companion.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
        }
        breakTicks++;
        level.destroyBlockProgress(companion.getId(), pos, Math.min(9, breakTicks * 10 / breakNeeded));
        if (breakTicks < breakNeeded) {
            return State.WORKING;
        }
        level.destroyBlockProgress(companion.getId(), pos, -1);
        if (level.destroyBlock(pos, true, companion)) {
            companion.hunger().addExhaustion(CompanionEntity.EXHAUSTION_BLOCK);
        }
        return State.BROKEN;
    }

    /** Clears the crack overlay; safe to call at any time. */
    public void stop(TaskContext c) {
        c.level().destroyBlockProgress(c.companion().getId(), pos, -1);
    }
}
