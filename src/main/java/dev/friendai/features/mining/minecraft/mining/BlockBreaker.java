package dev.friendai.features.mining.minecraft.mining;

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
    /** The blocks the breaker may dig away to uncover the target; leaves and vines for a feller. */
    private final Predicate<BlockState> diggable;
    /**
     * The construction options, kept so {@link #allowingSupport()} can rebuild the same breaker. They are not read
     * after construction: the {@link Approach} already holds its copy.
     */
    private final boolean mayPlace;

    private final PlacementLedger ledger;
    private final boolean routeDigging;

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
        this(
                candidates,
                stillValid,
                requireSight,
                mayPlace,
                digToTarget,
                allowBreakingSupport,
                ledger,
                state -> true,
                true);
    }

    /**
     * @param diggable which blocks may be dug away to uncover the target; the target itself is always excluded. A
     *     feller passes {@link SoftCover#isCover} so it never mines stone or dirt to reach a log. A breaker with a
     *     restricted set digs only those blocks, and only from the stance it walked to: its route never digs, because
     *     tunnelling through anything else would be work its caller did not ask for.
     */
    public BlockBreaker(
            Collection<BlockPos> candidates,
            Predicate<BlockState> stillValid,
            boolean requireSight,
            boolean mayPlace,
            boolean digToTarget,
            boolean allowBreakingSupport,
            PlacementLedger ledger,
            Predicate<BlockState> diggable) {
        this(
                candidates,
                stillValid,
                requireSight,
                mayPlace,
                digToTarget,
                allowBreakingSupport,
                ledger,
                diggable,
                false);
    }

    private BlockBreaker(
            Collection<BlockPos> candidates,
            Predicate<BlockState> stillValid,
            boolean requireSight,
            boolean mayPlace,
            boolean digToTarget,
            boolean allowBreakingSupport,
            PlacementLedger ledger,
            Predicate<BlockState> diggable,
            boolean routeDigging) {
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("BlockBreaker needs at least one candidate");
        }
        this.candidates = List.copyOf(candidates);
        this.stillValid = stillValid;
        this.requireSight = requireSight;
        this.digToTarget = digToTarget;
        this.allowBreakingSupport = allowBreakingSupport;
        this.diggable = diggable;
        this.mayPlace = mayPlace;
        this.ledger = ledger;
        this.routeDigging = routeDigging;
        this.approach = new Approach(this.candidates, mayPlace, ledger, requireSight, digToTarget ? diggable : null);
        if (!routeDigging) {
            this.approach.walkWithoutDigging();
        }
    }

    /** The candidate the breaker settled on, or null while it is still walking. */
    public BlockPos target() {
        return target;
    }

    /** Every block this breaker may break, so the caller can blacklist them together. */
    public List<BlockPos> candidates() {
        return candidates;
    }

    /**
     * Blocks the walking route may never dig, as ids or {@code #tag}s. A feller protects its logs here: the route
     * would otherwise tunnel through the trunk and destroy a log the task never records as cut.
     */
    public BlockBreaker protectRoute(List<String> blocks) {
        approach.avoidBreaking(blocks);
        return this;
    }

    /**
     * The same blocks under the same rules, but allowed to remove the block the companion stands on. For a job whose
     * remaining target is under its own feet and whose drop has somewhere safe to land: stepping off is the work, and
     * stopping one block short is not. Call it after {@link #stop}; support breaking is otherwise refused.
     */
    public BlockBreaker allowingSupport() {
        return new BlockBreaker(
                candidates, stillValid, requireSight, mayPlace, digToTarget, true, ledger, diggable, routeDigging);
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
                    BlockPos wanted = target != null ? target : candidates.get(0);
                    // No stance from which the block is visible can also be a buried block: when it is not in
                    // view from where the companion stands either, the cover is the reason and the caller can
                    // say so. A block in plain view that the route cannot reach is reported as unreachable.
                    if (requireSight && !digToTarget && !Sight.canSee(c.level(), companion, wanted)) {
                        refusal = null;
                        return State.OUT_OF_SIGHT;
                    }
                    refusal = new MiningRefusal.OutOfReach(wanted, "no route");
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
            // Breaking the cover where the companion stands is one block of work; stepping to another stance can
            // walk it off a scaffold beside a crown, so try the dig first and move only when nothing may be dug.
            if (digToTarget && dig(c)) {
                return State.WORKING;
            }
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
                // dig() above already reported that there is nothing this breaker may break to uncover it.
                return State.OUT_OF_SIGHT;
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

    /** Digs away a block that hides the target; true while there is something left to dig. */
    private boolean dig(TaskContext c) {
        if (dug >= MAX_DUG) {
            return false;
        }
        var companion = c.companion();
        BlockPos obstruction = null;
        // Sight hides the target from any of the aims a player may use; the centre ray often meets a solid block of
        // the structure while a side face is only behind cover, so look for diggable cover across the same aims.
        for (BlockPos candidate : Sight.obstructions(c.level(), companion, target)) {
            BlockState state = c.level().getBlockState(candidate);
            if (state.getDestroySpeed(c.level(), candidate) < 0
                    || !companion.hands().canInteract(candidate)
                    || !diggable.test(state)) {
                continue;
            }
            obstruction = candidate;
            break;
        }
        if (obstruction == null) {
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
