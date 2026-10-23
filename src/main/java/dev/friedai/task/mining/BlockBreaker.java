package dev.friedai.task.mining;

import dev.friedai.task.TaskContext;
import dev.friedai.task.nav.Approach;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Walks to one block, breaks it and collects the drops. One breaker per block: the caller decides what each
 * returned {@link State} means for its own task ({@code mine_blocks} searches again, {@code mine_block_at} fails).
 */
public final class BlockBreaker {
    public enum State { WORKING, BROKEN, GONE, UNREACHABLE }

    private static final int COLLECT_TICKS = 10;
    private static final double COLLECT_RADIUS = 4.0;
    private static final int SWING_INTERVAL = 5;

    private enum Phase { WALK, BREAK, COLLECT }

    private final BlockPos target;
    private final Predicate<BlockState> stillValid;
    private final Approach approach;

    private Phase phase = Phase.WALK;
    private int breakTicks;
    private int breakNeeded;
    private int collectTicks;

    /** @param stillValid whether the block is still the one this breaker was made for; when false the breaker reports {@link State#GONE} */
    public BlockBreaker(BlockPos target, Predicate<BlockState> stillValid) {
        this.target = target;
        this.stillValid = stillValid;
        this.approach = new Approach(target);
    }

    public BlockPos target() {
        return target;
    }

    public State tick(TaskContext c) {
        return switch (phase) {
            case WALK -> walk(c);
            case BREAK -> breakBlock(c);
            case COLLECT -> collect(c);
        };
    }

    /** Stops navigation and clears the crack overlay. */
    public void stop(TaskContext c) {
        approach.stop(c.companion());
        c.level().destroyBlockProgress(c.companion().getId(), target, -1);
    }

    private State walk(TaskContext c) {
        var companion = c.companion();
        if (!stillValid.test(c.level().getBlockState(target))) {
            return State.GONE;
        }
        switch (approach.tick(companion)) {
            case UNREACHABLE -> {
                return State.UNREACHABLE;
            }
            case MOVING -> {
                return State.WORKING;
            }
            case IN_REACH -> {
            }
        }
        BlockState state = c.level().getBlockState(target);
        ToolSelector.equipBest(companion, state);
        var tool = companion.getMainHandItem();
        boolean correct = !state.requiresCorrectToolForDrops() || tool.isCorrectToolForDrops(state);
        breakNeeded = BreakProgress.ticksToBreak(state.getDestroySpeed(c.level(), target), tool.getDestroySpeed(state), correct);
        breakTicks = 0;
        phase = Phase.BREAK;
        return State.WORKING;
    }

    private State breakBlock(TaskContext c) {
        var companion = c.companion();
        ServerLevel level = c.level();
        if (!stillValid.test(level.getBlockState(target))) {
            level.destroyBlockProgress(companion.getId(), target, -1);
            return State.GONE;
        }
        Vec3 center = Vec3.atCenterOf(target);
        companion.getLookControl().setLookAt(center.x, center.y, center.z);
        if (breakTicks % SWING_INTERVAL == 0) {
            companion.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
        }
        breakTicks++;
        level.destroyBlockProgress(companion.getId(), target, Math.min(9, breakTicks * 10 / breakNeeded));
        if (breakTicks >= breakNeeded) {
            level.destroyBlockProgress(companion.getId(), target, -1);
            level.destroyBlock(target, true, companion);
            collectTicks = 0;
            phase = Phase.COLLECT;
        }
        return State.WORKING;
    }

    private State collect(TaskContext c) {
        c.companion().collectNearbyItems(COLLECT_RADIUS);
        if (++collectTicks >= COLLECT_TICKS) {
            return State.BROKEN;
        }
        return State.WORKING;
    }
}
