package dev.friendai.task.mining;

import dev.friendai.task.TaskContext;
import dev.friendai.task.nav.Approach;
import dev.friendai.task.nav.Sight;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

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

    private enum Phase { WALK, BREAK }

    private final BlockPos target;
    private final Predicate<BlockState> stillValid;
    private final boolean requireSight;
    private final Approach approach;

    private Phase phase = Phase.WALK;
    private BreakAction action;

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
        };
    }

    /** Stops navigation and clears the crack overlay. */
    public void stop(TaskContext c) {
        approach.stop(c.companion());
        if (action != null) {
            action.stop(c);
        } else {
            c.level().destroyBlockProgress(c.companion().getId(), target, -1);
        }
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
        action = new BreakAction(target);
        phase = Phase.BREAK;
        return State.WORKING;
    }

    private State breakBlock(TaskContext c) {
        return switch (action.tick(c, stillValid)) {
            case WORKING -> State.WORKING;
            case BROKEN -> State.BROKEN;
            case GONE -> State.GONE;
        };
    }
}
