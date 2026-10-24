package dev.friendai.task.mining;

import dev.friendai.task.TaskContext;
import dev.friendai.task.nav.Approach;
import dev.friendai.task.nav.Sight;
import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Walks to one of the blocks it was given, breaks the one it reached and collects the drops. The caller decides what
 * each returned {@link State} means for its own task ({@code mine_blocks} searches again, {@code mine_block_at} fails).
 *
 * <p>Unless the caller says otherwise, the block must be in the companion's line of sight, as vanilla targeting
 * requires for a player: a block behind leaves or a wall cannot be mined through them. When the current stance has
 * no view, the companion steps to a free cell nearby that has one.
 */
public final class BlockBreaker {
    public enum State { WORKING, BROKEN, GONE, UNREACHABLE, PROTECTED, OUT_OF_SIGHT }

    private enum Phase { WALK, BREAK }

    private final List<BlockPos> candidates;
    private final Predicate<BlockState> stillValid;
    private final boolean requireSight;
    private final Approach approach;

    /** The candidate the companion reached and is working on, or null before it reached one. */
    private BlockPos target;
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
        this(List.of(target), stillValid, requireSight, false);
    }

    /**
     * Breaks whichever of several candidates the companion reaches first; {@link #target()} reports the one chosen.
     * The route never builds, so the caller's material is not spent on the way.
     */
    public BlockBreaker(Collection<BlockPos> candidates, Predicate<BlockState> stillValid) {
        this(candidates, stillValid, true, false);
    }

    /**
     * @param mayPlace whether the route may pillar or bridge to get within reach, spending the companion's material.
     *     Needed for a block that cannot be reached from the ground, like the top of a log column.
     */
    public BlockBreaker(
            Collection<BlockPos> candidates, Predicate<BlockState> stillValid, boolean requireSight, boolean mayPlace) {
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("BlockBreaker needs at least one candidate");
        }
        this.candidates = List.copyOf(candidates);
        this.stillValid = stillValid;
        this.requireSight = requireSight;
        this.approach = new Approach(this.candidates, mayPlace);
    }

    /** The candidate the breaker settled on, or null while it is still walking. */
    public BlockPos target() {
        return target;
    }

    /** Every block this breaker may break, so the caller can blacklist them together. */
    public List<BlockPos> candidates() {
        return candidates;
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
        }
        for (BlockPos pos : target != null ? List.of(target) : candidates) {
            c.level().destroyBlockProgress(c.companion().getId(), pos, -1);
        }
    }

    private State walk(TaskContext c) {
        var companion = c.companion();
        if (allGone(c)) {
            return State.GONE;
        }
        switch (approach.tick(companion)) {
            case UNREACHABLE -> {
                return State.UNREACHABLE;
            }
            case MOVING -> {
                return State.WORKING;
            }
            case IN_REACH -> target = approach.target();
        }
        if (!stillValid.test(c.level().getBlockState(target))) {
            return State.GONE;
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

    private boolean allGone(TaskContext c) {
        for (BlockPos pos : candidates) {
            if (stillValid.test(c.level().getBlockState(pos))) {
                return false;
            }
        }
        return true;
    }

    private State breakBlock(TaskContext c) {
        return switch (action.tick(c, stillValid)) {
            case WORKING -> State.WORKING;
            case BROKEN -> State.BROKEN;
            case GONE -> State.GONE;
        };
    }
}
