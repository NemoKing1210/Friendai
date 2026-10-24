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
 * Breaks one block over time: equips the best tool, swings, shows the crack overlay, destroys the block and picks
 * up its drops. The caller decides what each returned state means for its own task.
 */
public final class BreakAction {
    public enum State { WORKING, BROKEN, GONE }

    private static final int COLLECT_TICKS = 10;
    private static final double COLLECT_RADIUS = 4.0;
    private static final int SWING_INTERVAL = 5;

    private enum Phase { BREAK, COLLECT, DONE }

    private final BlockPos pos;
    private Phase phase = Phase.BREAK;
    private int breakTicks;
    private int breakNeeded;
    private int collectTicks;

    public BreakAction(BlockPos pos) {
        this.pos = pos;
    }

    public BlockPos pos() {
        return pos;
    }

    public State tick(TaskContext c, Predicate<BlockState> stillValid) {
        return switch (phase) {
            case BREAK -> breakTick(c, stillValid);
            case COLLECT -> collect(c);
            case DONE -> State.BROKEN;
        };
    }

    /** Clears the crack overlay; safe to call at any time. */
    public void stop(TaskContext c) {
        c.level().destroyBlockProgress(c.companion().getId(), pos, -1);
    }

    private State breakTick(TaskContext c, Predicate<BlockState> stillValid) {
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
        if (breakTicks >= breakNeeded) {
            level.destroyBlockProgress(companion.getId(), pos, -1);
            if (level.destroyBlock(pos, true, companion)) {
                companion.hunger().addExhaustion(CompanionEntity.EXHAUSTION_BLOCK);
            }
            collectTicks = 0;
            phase = Phase.COLLECT;
        }
        return State.WORKING;
    }

    private State collect(TaskContext c) {
        c.companion().collectNearbyItems(COLLECT_RADIUS);
        if (++collectTicks >= COLLECT_TICKS) {
            phase = Phase.DONE;
            return State.BROKEN;
        }
        return State.WORKING;
    }
}
