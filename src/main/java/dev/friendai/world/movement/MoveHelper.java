package dev.friendai.world.movement;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.foundation.spatial.Cell;
import dev.friendai.foundation.spatial.GoalPoint;
import dev.friendai.foundation.spatial.Pos;
import dev.friendai.task.TaskContext;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.phys.Vec3;

/**
 * Walks a mob toward a point, recomputing the path periodically and noticing when it makes no progress.
 *
 * <p>For a companion the walking is done by its {@link Navigator}, which can dig, pillar and bridge on the way; every
 * other mob keeps vanilla navigation.
 */
public final class MoveHelper {
    public enum State {
        MOVING,
        ARRIVED,
        STUCK
    }

    private static final int REPATH_TICKS = 10;
    private static final int STUCK_CHECK_TICKS = 100;
    private static final double MIN_PROGRESS = 0.5;
    /**
     * How far above or below the requested point a node still counts as the goal. One block allows a target on a step
     * or a slab to be reached from the ground below it; a wider band let a body at the foot of a pillar "reach" a
     * point three blocks over its head.
     */
    private static final double VERTICAL = 1.0;

    private final double speed;
    private final double arriveDistance;
    private final double goalTolerance;
    private final int snapDrop;
    private final boolean mayPlace;
    private int ticks;
    private int sinceCheckpoint;
    private double checkpointDistance;
    private Navigator navigator;
    private FlightNavigator flightNavigator;
    private Vec3 lastGoal;
    /** The terrain view under a target that is being snapped to the ground; rebuilt every few ticks, not every call. */
    private LevelCellView terrain;

    private int terrainTick = Integer.MIN_VALUE;
    /** How long the terrain view under a re-targeted goal is reused; an airborne owner asks on every tick. */
    private static final int TERRAIN_TICKS = 5;
    /** Distance to the owner while following, or NaN: the pace policy runs automatic followers by it. */
    private double ownerDistance = Double.NaN;

    public MoveHelper(double speed, double arriveDistance) {
        this(speed, arriveDistance, 1.0, 16, true);
    }

    /** A task-owned positioning route may forbid spending its construction material. */
    public MoveHelper(double speed, double arriveDistance, boolean mayPlace) {
        this(speed, arriveDistance, 1.0, 16, mayPlace);
    }

    public MoveHelper(double speed, double arriveDistance, double goalTolerance, int snapDrop) {
        this(speed, arriveDistance, goalTolerance, snapDrop, true);
    }

    private MoveHelper(double speed, double arriveDistance, double goalTolerance, int snapDrop, boolean mayPlace) {
        this.speed = speed;
        this.arriveDistance = arriveDistance;
        this.goalTolerance = goalTolerance;
        this.snapDrop = snapDrop;
        this.mayPlace = mayPlace;
    }

    /**
     * Tells the next {@link #tick} that the companion is trailing its owner at this distance, so one pace decision per
     * tick covers it. Call it every tick while following; {@code NaN} (the default) means not following.
     */
    public void following(double ownerDistance) {
        this.ownerDistance = ownerDistance;
    }

    public State tick(PathfinderMob mob, Vec3 target) {
        if (mob instanceof CompanionEntity) {
            throw new IllegalArgumentException("companion movement requires its task context");
        }
        double dx = target.x - mob.getX();
        double dy = target.y - mob.getY();
        double dz = target.z - mob.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal <= arriveDistance && Math.abs(dy) <= VERTICAL) {
            mob.getNavigation().stop();
            return State.ARRIVED;
        }
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (ticks % REPATH_TICKS == 0) {
            mob.getNavigation().moveTo(target.x, target.y, target.z, speed);
        }
        ticks++;
        if (sinceCheckpoint == 0) {
            checkpointDistance = distance;
        }
        sinceCheckpoint++;
        if (sinceCheckpoint >= STUCK_CHECK_TICKS) {
            if (checkpointDistance - distance < MIN_PROGRESS) {
                return State.STUCK;
            }
            sinceCheckpoint = 0;
        }
        return State.MOVING;
    }

    public State tick(TaskContext context, Vec3 target) {
        CompanionEntity companion = context.companion();
        boolean followingOwner = !Double.isNaN(ownerDistance);
        double paceDistance =
                followingOwner ? ownerDistance : (navigator != null ? navigator.remainingDistance() : 0.0);
        companion.pace().update(followingOwner, paceDistance, paceDistance);
        boolean passiveFollow = context.defaultActivity() && context.passiveFlightAllowed();
        boolean mayFly = context.flightAuthorized() || passiveFollow;
        if (companion.creativeMode() && mayFly) {
            if (navigator != null) {
                navigator.stop(context);
                navigator = null;
            }
            if (flightNavigator == null) flightNavigator = new FlightNavigator();
            return switch (flightNavigator.tick(context, target, passiveFollow)) {
                case ARRIVED -> State.ARRIVED;
                case UNREACHABLE -> State.STUCK;
                case SEARCHING, MOVING -> State.MOVING;
            };
        }
        if (flightNavigator != null) {
            flightNavigator.stop(context);
            flightNavigator = null;
        }
        if (navigator == null) {
            navigator = new Navigator(context, mayPlace, null, null);
        }
        if (lastGoal == null || lastGoal.distanceToSqr(target) > goalTolerance * goalTolerance) {
            lastGoal = target;
            navigator.setGoal(goalFor(companion, target));
        }
        return switch (navigator.tick(context)) {
            case ARRIVED -> finishAlignment(context);
            case UNREACHABLE -> State.STUCK;
            default -> State.MOVING;
        };
    }

    private GoalPoint goalFor(CompanionEntity companion, Vec3 target) {
        LevelCellView view = terrain(companion);
        int tx = (int) Math.floor(target.x);
        int ty = (int) Math.floor(target.y);
        int tz = (int) Math.floor(target.z);
        // A target inside a solid block or a fence (a post to tie to, a block to reach) has no ground under it to
        // snap to: searching downward would pass through the floor it stands on and send the companion beneath it.
        Cell.Kind inside = view.at(tx, ty, tz).kind();
        Pos stand = inside == Cell.Kind.SOLID || inside == Cell.Kind.TALL
                ? new Pos(tx, ty, tz)
                : GoalPoint.snapDown(view, tx, ty, tz, snapDrop);
        return new GoalPoint(stand.x() + 0.5, stand.y(), stand.z() + 0.5, arriveDistance, VERTICAL);
    }

    /**
     * The terrain view for this re-target. Snapping a moving target down happens every few blocks, so the view is
     * kept for a few ticks instead of rebuilding a cache for each one.
     */
    private LevelCellView terrain(CompanionEntity companion) {
        int now = companion.tickCount;
        if (terrain == null || now < terrainTick || now - terrainTick >= TERRAIN_TICKS) {
            terrain = LevelCellView.terrain(companion, 128);
            terrainTick = now;
        }
        return terrain;
    }

    /** Node centres approximate a goal band; an exact positioning caller still needs the real feet inside it. */
    private State finishAlignment(TaskContext context) {
        GoalPoint point = (GoalPoint) navigator.goal();
        return align(context, point.x(), point.z());
    }

    /**
     * Walks the body the last stretch toward a point on its own level once the route is done. A caller whose arrival
     * test is stricter than the goal band uses this instead of stopping wherever the band happened to end.
     */
    State align(TaskContext context, double x, double z) {
        return align(context, x, z, arriveDistance);
    }

    /**
     * The same walk with an explicit horizontal allowance, for a caller whose arrival test also covers height: the
     * horizontal gap it must close is smaller when the target is above or below it.
     */
    State align(TaskContext context, double x, double z, double horizontalAllowance) {
        CompanionEntity companion = context.companion();
        double horizontal = Math.hypot(x - companion.getX(), z - companion.getZ());
        if (horizontal <= horizontalAllowance) {
            if (navigator != null) {
                navigator.stop(context);
            }
            return State.ARRIVED;
        }
        companion.setPathing(true);
        companion.getMoveControl().setWantedPosition(x, companion.getY(), z, speed);
        if (sinceCheckpoint == 0) {
            checkpointDistance = horizontal;
        }
        if (++sinceCheckpoint >= STUCK_CHECK_TICKS) {
            sinceCheckpoint = 0;
            if (checkpointDistance - horizontal < Math.min(MIN_PROGRESS, horizontalAllowance)) {
                if (navigator != null) {
                    navigator.stop(context);
                }
                return State.STUCK;
            }
        }
        return State.MOVING;
    }

    /**
     * One line for the movement debug view and a failing trace: which route owns the body and what its last search
     * said. Without it a follower that stops short says only that the task is running.
     */
    public String debugStatus() {
        if (flightNavigator != null) {
            return "flight " + flightNavigator.debugStatus();
        }
        if (navigator == null) {
            return "no route";
        }
        String search = navigator.lastSearch();
        return navigator.state()
                + (navigator.plan() == null ? ", no route" : ", walking")
                + ", "
                + (search.isEmpty() ? "searching" : search);
    }

    public void reset(TaskContext c) {
        reset(c, false);
    }

    private void reset(TaskContext c, boolean keepHovering) {
        if (flightNavigator != null) {
            flightNavigator.stop(c, keepHovering);
            flightNavigator = null;
        }
        if (navigator != null) {
            navigator.stop(c);
            navigator = null;
        }
        ticks = 0;
        sinceCheckpoint = 0;
        checkpointDistance = 0;
        lastGoal = null;
        terrain = null;
        terrainTick = Integer.MIN_VALUE;
    }

    public void stop(TaskContext c) {
        reset(c);
        c.companion().getNavigation().stop();
    }

    /** Stops at the requested position without dropping creative hover. */
    public void stopAtDestination(TaskContext c) {
        reset(c, true);
        c.companion().getNavigation().stop();
    }
}
