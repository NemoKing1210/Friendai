package dev.friendai.task.mining;

import dev.friendai.features.navigation.minecraft.nav.Approach;
import dev.friendai.features.navigation.minecraft.nav.MoveHelper;
import dev.friendai.features.navigation.minecraft.nav.PlacementLedger;
import dev.friendai.features.navigation.minecraft.nav.Sight;
import dev.friendai.task.TaskContext;
import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Walks to one of the blocks it was given and breaks the one it reached. The drop falls to the ground where a
 * player's would, so the caller sweeps it up. The caller decides what each returned {@link State} means for its own
 * task ({@code mine_blocks} searches again, {@code mine_block_at} fails).
 *
 * <p>Unless the caller says otherwise, the block must be in the companion's line of sight, as vanilla targeting
 * requires for a player: a block behind leaves or a wall cannot be mined through them. When the current stance has
 * no view, the companion steps to a free cell nearby that has one.
 */
public final class BlockBreaker {
    public enum State {
        WORKING,
        BROKEN,
        GONE,
        UNREACHABLE,
        PROTECTED,
        OUT_OF_SIGHT
    }

    private enum Phase {
        WALK,
        BREAK
    }

    /** How many blocks one target may have dug away before it is given up as hidden. */
    private static final int MAX_DUG = 12;
    /** How many ticks the companion may spend centring itself on a view spot it has reached without a view. */
    private static final int SETTLE_TICKS = 5;

    private final List<BlockPos> candidates;
    private final Predicate<BlockState> stillValid;
    private final boolean requireSight;
    /** Whether the companion digs away what hides the target, as a player digging into a hill does. */
    private final boolean digToTarget;
    /** Whether the breaker may remove a temporary block directly beneath the companion. */
    private final boolean allowBreakingSupport;

    private final Approach approach;
    /** A precise stance goal: interaction reach alone does not put the eyes around the obstruction. */
    private MoveHelper viewApproach;

    private Vec3 viewPoint;
    private boolean viewFailed;

    /** The candidate the companion reached and is working on, or null before it reached one. */
    private BlockPos target;

    private int settleTicks;
    private Phase phase = Phase.WALK;
    private BreakAction action;
    /** The block hiding the target that is being dug away right now, and how many were dug for this target. */
    private BlockPos digging;

    private BreakAction digAction;
    private int dug;
    /** The typed cause of the last refusal, so a task can say what refused instead of "is protected". */
    private MiningRefusal refusal;

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

    /** A cleanup-only variant that may remove the temporary block the companion is standing on. */
    public BlockBreaker(
            BlockPos target, Predicate<BlockState> stillValid, boolean requireSight, boolean allowBreakingSupport) {
        this(List.of(target), stillValid, requireSight, false, false, allowBreakingSupport);
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
        this(candidates, stillValid, requireSight, mayPlace, false);
    }

    /**
     * @param digToTarget whether the companion digs away a block hiding the target instead of reporting
     *     {@link State#OUT_OF_SIGHT}. Mining a kind of block means getting it, so {@code mine_blocks} asks for this;
     *     breaking one block the player named keeps the plain line-of-sight rule.
     */
    public BlockBreaker(
            Collection<BlockPos> candidates,
            Predicate<BlockState> stillValid,
            boolean requireSight,
            boolean mayPlace,
            boolean digToTarget) {
        this(candidates, stillValid, requireSight, mayPlace, digToTarget, false);
    }

    /** Full constructor; support breaking is reserved for cleanup of a block the companion itself placed. */
    public BlockBreaker(
            Collection<BlockPos> candidates,
            Predicate<BlockState> stillValid,
            boolean requireSight,
            boolean mayPlace,
            boolean digToTarget,
            boolean allowBreakingSupport) {
        this(candidates, stillValid, requireSight, mayPlace, digToTarget, allowBreakingSupport, null);
    }

    /**
     * @param ledger records the blocks a pillaring or bridging route places, so the caller can remove them when the
     *     job ends; null when the route must not build or its placements are meant to stay.
     */
    public BlockBreaker(
            Collection<BlockPos> candidates,
            Predicate<BlockState> stillValid,
            boolean requireSight,
            boolean mayPlace,
            boolean digToTarget,
            boolean allowBreakingSupport,
            PlacementLedger ledger) {
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("BlockBreaker needs at least one candidate");
        }
        this.candidates = List.copyOf(candidates);
        this.stillValid = stillValid;
        this.requireSight = requireSight;
        this.digToTarget = digToTarget;
        this.allowBreakingSupport = allowBreakingSupport;
        this.approach = new Approach(this.candidates, mayPlace, ledger);
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
        approach.stop(c);
        if (viewApproach != null) {
            viewApproach.stop(c);
            viewApproach = null;
        }
        if (action != null) {
            action.stop(c);
        }
        if (digAction != null) {
            digAction.stop(c);
            digAction = null;
            digging = null;
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
        if (viewApproach == null) {
            switch (approach.tick(c)) {
                case UNREACHABLE -> {
                    refusal = new MiningRefusal.OutOfReach(target != null ? target : candidates.get(0), "no route");
                    return State.UNREACHABLE;
                }
                case MOVING -> {
                    return State.WORKING;
                }
                case IN_REACH -> target = approach.target();
            }
        }
        if (!stillValid.test(c.level().getBlockState(target))) {
            return State.GONE;
        }
        if (!companion.hands().canInteract(target)) {
            refusal = new MiningRefusal.ServerProtected(target);
            return State.PROTECTED;
        }
        if (requireSight && !Sight.canSee(c.level(), companion, target)) {
            if (!viewFailed && viewApproach == null) {
                BlockPos view = Sight.viewSpot(c.level(), companion, target);
                if (view != null) {
                    viewApproach = new MoveHelper(1.0, 0.15, false);
                    viewPoint = Vec3.atBottomCenterOf(view);
                }
            }
            if (viewApproach != null) {
                MoveHelper.State state = viewApproach.tick(c, viewPoint);
                if (state == MoveHelper.State.MOVING) {
                    return State.WORKING;
                }
                if (settleTicks < SETTLE_TICKS && !Sight.canSee(c.level(), companion, target)) {
                    // Arrival is judged within a tolerance, but the view spot may be only as wide as the
                    // companion: settle onto its centre before deciding there is no view from it.
                    settleTicks++;
                    companion.getMoveControl().setWantedPosition(viewPoint.x, viewPoint.y, viewPoint.z, 1.0);
                    return State.WORKING;
                }
                viewApproach.stop(c);
                viewApproach = null;
                viewFailed = true;
            }
            if (!Sight.canSee(c.level(), companion, target)) {
                if (!digToTarget || !dig(c)) {
                    return State.OUT_OF_SIGHT;
                }
                return State.WORKING;
            }
        }
        if (viewApproach != null) {
            viewApproach.stop(c);
            viewApproach = null;
        }
        action = new BreakAction(target, allowBreakingSupport);
        phase = Phase.BREAK;
        return State.WORKING;
    }

    /** Digs away the block that hides the target; true while there is something left to dig. */
    private boolean dig(TaskContext c) {
        if (dug >= MAX_DUG) {
            return false;
        }
        var companion = c.companion();
        BlockPos obstruction = Sight.firstBlockOnRay(c.level(), companion, target);
        if (obstruction == null
                || c.level().getBlockState(obstruction).getDestroySpeed(c.level(), obstruction) < 0
                || !companion.hands().canInteract(obstruction)) {
            return false;
        }
        if (digAction == null || !obstruction.equals(digging)) {
            if (digAction != null) {
                digAction.stop(c);
            }
            digAction = new BreakAction(obstruction);
            digging = obstruction;
            dug++;
        }
        BreakAction.State digState = digAction.tick(c, state -> !state.isAir());
        if (digState == BreakAction.State.WORKING) {
            return true;
        }
        if (digState == BreakAction.State.BLOCKED && digAction.refusal() != null) {
            refusal = digAction.refusal();
        }
        digAction = null;
        digging = null;
        return digState != BreakAction.State.BLOCKED;
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
            case BLOCKED -> {
                refusal = action.refusal();
                yield State.PROTECTED;
            }
        };
    }

    /** The typed cause of the last refusal, or null when there was none. */
    public MiningRefusal refusal() {
        return refusal;
    }

    /** A model-facing sentence for a state this breaker just returned; names the refusal and its way out. */
    public String reason(State state) {
        if (refusal != null) {
            return refusal.describe() + "; " + refusal.remedy();
        }
        return switch (state) {
            case PROTECTED -> "the block is protected";
            case UNREACHABLE -> "can't reach it";
            case OUT_OF_SIGHT -> "another block hides it";
            default -> "the block is gone";
        };
    }
}
