package dev.friendai.task.nav;

import dev.friendai.creative.CreativeAction;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.pathing.GoalReach;
import dev.friendai.pathing.Pos;
import dev.friendai.task.TaskContext;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Walks a companion until one of the blocks is within reach. A companion walks with its {@link Navigator}, so it may
 * dig through what is in the way; {@link GoalReach} only counts a place the companion can actually see the block
 * from.
 *
 * <p>Building on the way (pillar, bridge) is off unless the caller asks for it: an approach that is only there to use
 * a block must not spend the material a task brought for its own work. {@code MineBlocksTask} turns it on, because a
 * block above the ground can only be reached by pillaring.
 */
public final class Approach {
    public enum State {
        MOVING,
        IN_REACH,
        UNREACHABLE
    }

    /** How close the companion's eyes must be to the block centre to act on it. */
    public static final double REACH = 4.0;
    /** Up to this eye distance a plain walk goes first; planning is for blocks the walk cannot bring within reach. */
    private static final double WALK_FIRST_DISTANCE = 8.0;
    /** How many ticks of walking the last metre may take without progress. */
    private static final int CLOSER_REPATH_TICKS = 10;
    /**
     * The plain walk only has to cover the last few blocks. A long window made every block a task cannot reach cost five
     * seconds, and the planner or the caller has a budget of its own.
     */
    private static final int CLOSER_STUCK_TICKS = 20;

    private static final double CLOSER_MIN_PROGRESS = 0.5;

    /** Walking first, then (if that stalls) planning a route, then walking again to close the last metre. */
    private enum Phase {
        WALK,
        PLAN,
        LAST_WALK
    }

    private final List<BlockPos> candidates;
    /** Whether the route may pillar or bridge. Off for an approach that must not spend the caller's material. */
    private final boolean mayPlace;
    /** Where blocks placed by the route are remembered, or null when they are not the caller's to remove. */
    private final PlacementLedger ledger;
    /** Cells a caller needs to reserve for future work, such as the rest of a blueprint being built. */
    private final Set<BlockPos> excludedFlightCells;

    private Navigator navigator;
    private FlightNavigator flightNavigator;
    private Vec3 flightGoal;
    private BlockPos reached;
    private Phase phase;
    private int closerTicks;
    private int closerCheckpoint;
    private double closerDistance = Double.POSITIVE_INFINITY;

    public Approach(BlockPos target) {
        this(List.of(target));
    }

    /** Approaches the nearest of several candidate blocks; {@link #target()} reports the one reached. */
    public Approach(Collection<BlockPos> candidates) {
        this(candidates, false);
    }

    /**
     * @param mayPlace whether reaching the block may pillar or bridge. A block out of reach can be reached that way,
     *     at the cost of the material in the companion's inventory.
     */
    public Approach(Collection<BlockPos> candidates, boolean mayPlace) {
        this(candidates, mayPlace, Set.of(), null);
    }

    /** @param ledger records blocks the route places, so a task that pillared to reach a block can remove it. */
    public Approach(Collection<BlockPos> candidates, boolean mayPlace, PlacementLedger ledger) {
        this(candidates, mayPlace, Set.of(), ledger);
    }

    /** Excludes planned cells from creative flight landings so an approach cannot trap the companion in a build. */
    public Approach(Collection<BlockPos> candidates, boolean mayPlace, Set<BlockPos> excludedFlightCells) {
        this(candidates, mayPlace, excludedFlightCells, null);
    }

    private Approach(
            Collection<BlockPos> candidates,
            boolean mayPlace,
            Set<BlockPos> excludedFlightCells,
            PlacementLedger ledger) {
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("Approach needs at least one candidate");
        }
        this.candidates = List.copyOf(candidates);
        this.mayPlace = mayPlace;
        this.ledger = ledger;
        this.excludedFlightCells = Set.copyOf(excludedFlightCells);
    }
    /** The block the companion reached, or the first candidate before it got there. */
    public BlockPos target() {
        return reached != null ? reached : candidates.get(0);
    }

    public State tick(CompanionEntity companion) {
        return tick(companion.taskRunner().activeTaskContext());
    }

    /** Uses authorized creative flight when available; survival approaches keep their walking and planning path. */
    public State tick(TaskContext context) {
        CompanionEntity companion = context.companion();
        BlockPos near = nearestWithinReach(companion);
        if (near != null) {
            reached = near;
            stop(context, context.canUseCreativeAction(CreativeAction.FLIGHT));
            lookAt(companion, near);
            return State.IN_REACH;
        }
        if (companion.creativeMode() && context.canUseCreativeAction(CreativeAction.FLIGHT)) {
            if (navigator != null) {
                navigator.stop(context);
                navigator = null;
            }
            companion.getNavigation().stop();
            if (flightNavigator == null) flightNavigator = new FlightNavigator();
            if (flightGoal == null) flightGoal = flightApproachGoal(context);
            if (flightGoal == null) {
                stop(context);
                return State.UNREACHABLE;
            }
            return switch (flightNavigator.tick(context, flightGoal)) {
                case MOVING, SEARCHING -> State.MOVING;
                case UNREACHABLE -> {
                    stop(context);
                    yield State.UNREACHABLE;
                }
                case ARRIVED -> {
                    BlockPos arrived = nearestWithinReach(companion);
                    if (arrived == null) {
                        stop(context);
                        yield State.UNREACHABLE;
                    }
                    reached = arrived;
                    lookAt(companion, arrived);
                    stop(context, true);
                    yield State.IN_REACH;
                }
            };
        }
        if (flightNavigator != null) {
            flightNavigator.stop(context);
            flightNavigator = null;
            flightGoal = null;
        }
        if (phase == null) {
            // A block a few steps away is not worth a search: the plain walk answers in a fraction of the time, and
            // most approaches (open a chest, place a block, light a spot) are that close.
            double eyeDistance = companion.getEyePosition().distanceTo(Vec3.atCenterOf(nearest(companion)));
            phase = eyeDistance <= WALK_FIRST_DISTANCE ? Phase.WALK : Phase.PLAN;
        }
        if (phase == Phase.PLAN) {
            if (navigator == null) {
                navigator = new Navigator(companion, mayPlace, ledger);
                navigator.setGoal(goal(companion));
                companion.getNavigation().stop();
            }
            switch (navigator.tick(context)) {
                case MOVING, SEARCHING, GATHERING -> {
                    return State.MOVING;
                }
                case ARRIVED -> {
                    // The route ended: walk the last metre the way this class always did.
                    navigator.stop(context);
                    navigator = null;
                    enterLastWalk();
                }
                case UNREACHABLE -> {
                    // No route with digging or bridging: fall back to plain navigation, which may still get close.
                    navigator.stop(context);
                    navigator = null;
                    enterLastWalk();
                }
            }
        }
        State walked = closer(companion);
        if (walked == State.UNREACHABLE && phase == Phase.WALK) {
            // The plain walk cannot get closer: there is something in the way. Let the planner dig or bridge through
            // it.
            phase = Phase.PLAN;
            resetWalk();
            return State.MOVING;
        }
        return walked;
    }

    private void enterLastWalk() {
        phase = Phase.LAST_WALK;
        resetWalk();
    }

    private void resetWalk() {
        closerTicks = 0;
        closerCheckpoint = 0;
        closerDistance = Double.POSITIVE_INFINITY;
    }

    /**
     * Walks toward the nearest block with plain navigation until the eyes are within reach. Used for the last metre
     * after a planned route, and as the fallback when no route exists.
     */
    private State closer(CompanionEntity companion) {
        Vec3 center = Vec3.atCenterOf(nearest(companion));
        double distance = companion.getEyePosition().distanceTo(center);
        if (closerTicks % CLOSER_REPATH_TICKS == 0) {
            companion.getNavigation().moveTo(center.x, center.y, center.z, 1.0);
        }
        if (closerCheckpoint == 0) {
            closerDistance = distance;
        }
        if (++closerCheckpoint >= CLOSER_STUCK_TICKS) {
            if (closerDistance - distance < CLOSER_MIN_PROGRESS) {
                return State.UNREACHABLE;
            }
            closerCheckpoint = 0;
        }
        closerTicks++;
        return State.MOVING;
    }

    public void stop(CompanionEntity companion) {
        stop(companion.taskRunner().activeTaskContext());
    }

    public void stop(TaskContext context) {
        stop(context, false);
    }

    private void stop(TaskContext context, boolean keepHovering) {
        CompanionEntity companion = context.companion();
        companion.getNavigation().stop();
        if (navigator != null) {
            navigator.stop(context);
            navigator = null;
        }
        if (flightNavigator != null) {
            flightNavigator.stop(context, keepHovering);
            flightNavigator = null;
        }
        flightGoal = null;
    }

    /** Finds an unoccupied flight cell with enough reach left to tolerate FlightNavigator's arrival radius. */
    private Vec3 flightApproachGoal(TaskContext context) {
        CompanionEntity companion = context.companion();
        ServerLevel level = context.level();
        double maxEyeDistance = REACH - FlightNavigator.ARRIVAL_TOLERANCE - 0.1;
        Vec3 eye = companion.getEyePosition();
        Vec3 best = null;
        double bestTravel = Double.POSITIVE_INFINITY;
        int span = (int) Math.ceil(REACH);
        for (BlockPos target : candidates) {
            Vec3 center = Vec3.atCenterOf(target);
            for (int y = target.getY() - span; y <= target.getY() + span; y++) {
                for (int x = target.getX() - span; x <= target.getX() + span; x++) {
                    for (int z = target.getZ() - span; z <= target.getZ() + span; z++) {
                        Vec3 feet = new Vec3(x + 0.5, y, z + 0.5);
                        if (feet.add(0.0, companion.getEyeHeight(), 0.0).distanceTo(center) > maxEyeDistance) {
                            continue;
                        }
                        BlockPos feetCell = BlockPos.containing(feet);
                        if (excludedFlightCells.contains(feetCell) || excludedFlightCells.contains(feetCell.above())) {
                            continue;
                        }
                        AABB box = companion.getBoundingBox().move(feet.subtract(companion.position()));
                        if (!flightCellFree(level, companion, box)) {
                            continue;
                        }
                        double travel = companion.position().distanceToSqr(feet);
                        if (travel < bestTravel) {
                            best = feet;
                            bestTravel = travel;
                        }
                    }
                }
            }
        }
        return best;
    }

    private static boolean flightCellFree(ServerLevel level, CompanionEntity companion, AABB box) {
        if (box.minY < level.getMinY() || box.maxY > level.getMaxY() + 1.0) return false;
        for (int x : new int[] {
            BlockPos.containing(box.minX, box.minY, box.minZ).getX(),
            BlockPos.containing(box.maxX, box.minY, box.minZ).getX()
        }) {
            for (int z : new int[] {
                BlockPos.containing(box.minX, box.minY, box.minZ).getZ(),
                BlockPos.containing(box.minX, box.minY, box.maxZ).getZ()
            }) {
                if (!level.hasChunkAt(x, z)) return false;
            }
        }
        return level.noCollision(companion, box);
    }

    private GoalReach goal(CompanionEntity companion) {
        List<Pos> targets = candidates.stream()
                .map(pos -> new Pos(pos.getX(), pos.getY(), pos.getZ()))
                .toList();
        return new GoalReach(targets, REACH, companion.getEyeHeight());
    }

    /** The nearest candidate within reach of the eyes right now, or null. */
    private BlockPos nearestWithinReach(CompanionEntity companion) {
        Vec3 eye = companion.getEyePosition();
        BlockPos best = null;
        double bestDistance = REACH;
        for (BlockPos candidate : candidates) {
            double distance = eye.distanceTo(Vec3.atCenterOf(candidate));
            if (distance <= bestDistance) {
                best = candidate;
                bestDistance = distance;
            }
        }
        return best;
    }

    private BlockPos nearest(CompanionEntity companion) {
        Vec3 eye = companion.getEyePosition();
        BlockPos best = candidates.get(0);
        double bestDistance = Double.POSITIVE_INFINITY;
        for (BlockPos candidate : candidates) {
            double distance = eye.distanceTo(Vec3.atCenterOf(candidate));
            if (distance < bestDistance) {
                best = candidate;
                bestDistance = distance;
            }
        }
        return best;
    }

    private static void lookAt(CompanionEntity companion, BlockPos target) {
        Vec3 center = Vec3.atCenterOf(target);
        companion.getLookControl().setLookAt(center.x, center.y, center.z);
    }
}
