package dev.friendai.task.mining;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.task.TaskContext;
import dev.friendai.task.nav.Approach;
import dev.friendai.task.nav.Sight;
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
 *
 * <p>Unless the caller says otherwise, the block must be in the companion's line of sight, as vanilla targeting
 * requires for a player: a block behind leaves or a wall cannot be mined through them. When the current stance has
 * no view, the companion steps to a free cell nearby that has one.
 */
public final class BlockBreaker {
    public enum State { WORKING, BROKEN, GONE, UNREACHABLE, PROTECTED, OUT_OF_SIGHT }

    private static final int COLLECT_TICKS = 10;
    private static final double COLLECT_RADIUS = 4.0;
    private static final int SWING_INTERVAL = 5;

    private enum Phase { WALK, BREAK, COLLECT }

    private final BlockPos target;
    private final Predicate<BlockState> stillValid;
    private final boolean requireSight;
    private final Approach approach;

    private Phase phase = Phase.WALK;
    private int breakTicks;
    private int breakNeeded;
    private int collectTicks;

    /** @param stillValid whether the block is still the one this breaker was made for; when false the breaker reports {@link State#GONE} */
    public BlockBreaker(BlockPos target, Predicate<BlockState> stillValid) {
        this(target, stillValid, true);
    }

    /**
     * @param requireSight whether the block must be visible, as for a player. False only for a cell the companion is
     *     excavating on purpose or a block it placed itself, which may be buried behind other blocks.
     */
    public BlockBreaker(BlockPos target, Predicate<BlockState> stillValid, boolean requireSight) {
        this.target = target;
        this.stillValid = stillValid;
        this.requireSight = requireSight;
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
        if (!companion.hands().canInteract(target)) {
            return State.PROTECTED;
        }
        if (requireSight && !Sight.canSee(c.level(), companion, target)) {
            BlockPos view = Sight.viewSpot(c.level(), companion, target);
            if (view == null) {
                return State.OUT_OF_SIGHT;
            }
            companion.getNavigation().stop();
            companion.teleportTo(view.getX() + 0.5, view.getY(), view.getZ() + 0.5);
            if (!Sight.canSee(c.level(), companion, target)) {
                return State.OUT_OF_SIGHT;
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
            if (level.destroyBlock(target, true, companion)) {
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
            return State.BROKEN;
        }
        return State.WORKING;
    }
}
