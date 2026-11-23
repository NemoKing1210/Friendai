package dev.friendai.features.navigation.minecraft.task;

import dev.friendai.foundation.movement.OrbitCoverage;
import dev.friendai.foundation.movement.OrbitReport;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.world.movement.MoveHelper;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Walks a ring around a point or an entity, looking at its centre, for a number of laps.
 *
 * <p>Only a waypoint the body actually reached counts as coverage. A blocked waypoint first tries one wider and one
 * narrower arc around the same centre; when both fail it is recorded as blocked and the rest of the ring is walked so
 * the result can say how much of the requested orbit was covered instead of claiming an obstructed lap.
 */
public final class CircleTask implements Task {
    private static final int POINTS_PER_LAP = 8;
    private static final int LOOK_TICKS = 3;
    private static final int MAX_BLOCKED = 3;
    private static final int MAX_OFF_RING_ARRIVALS = 3;
    private static final int MAX_CENTER_JUMPS = 8;
    private static final double OUTWARD_BYPASS = 1.0;
    private static final double INWARD_BYPASS = 1.0;
    /** A centre that moved this far between ticks was not walked: the ring's definition changed under the task. */
    private static final double CENTER_JUMP = 2.0;

    /** An arrival must be at the attempted point on the attempted arc, not merely somewhere around the ring. */
    private static final double WAYPOINT_SLACK = 1.25;

    private final Supplier<Vec3> center;
    private final String centerLabel;
    private final double radius;
    private final int laps;
    private final MoveHelper mover = new MoveHelper(1.0, 1.0);
    private final OrbitCoverage coverage;
    private double startAngle;
    /** The centre as it was on the last tick; the look and each waypoint follow it. */
    private Vec3 anchor;
    /** A stable look supplier: the glance follows the anchor as recentres move it, without allocating per tick. */
    private final Supplier<Vec3> anchorLook = () -> anchor;

    private int point;
    private int attempt;
    private int jumps;
    private int blockedWaypoints;
    private int offRingArrivals;
    private boolean vanished;
    private String summary = "";

    public CircleTask(Supplier<Vec3> center, String centerLabel, double radius, int laps) {
        this.center = center;
        this.centerLabel = centerLabel;
        this.radius = radius;
        this.laps = laps;
        this.coverage = new OrbitCoverage(POINTS_PER_LAP * laps);
    }

    @Override
    public String type() {
        return "circle";
    }

    @Override
    public void start(TaskContext c) {
        anchor = center.get();
        if (anchor == null) {
            vanished = true;
            return;
        }
        Vec3 me = c.companion().position();
        startAngle = Math.atan2(me.z - anchor.z, me.x - anchor.x);
        mover.reset(c);
    }

    @Override
    public TaskStatus tick(TaskContext c) {
        if (vanished) {
            return fail("the " + centerLabel + " is gone");
        }
        Vec3 live = center.get();
        if (live == null) {
            return fail("the " + centerLabel + " is gone");
        }
        if (anchor != null && live.distanceTo(anchor) > CENTER_JUMP) {
            if (++jumps > MAX_CENTER_JUMPS) {
                return fail("the " + centerLabel + " keeps moving");
            }
            restartAttempt(c);
        }
        anchor = live;
        if (point >= coverage.total()) {
            return finish();
        }
        c.companion().body().holdLook(anchorLook, LOOK_TICKS);
        Vec3 waypoint = waypointAt(c);
        switch (mover.tick(c, waypoint)) {
            case ARRIVED -> {
                if (!reachedWaypoint(c, waypoint)) {
                    // The route ended near the point but the body never stood at it: a blocked cell or a rebuilt
                    // waypoint must not become progress until the position agrees.
                    if (nextAttempt(c)) {
                        return TaskStatus.RUNNING;
                    }
                    if (++offRingArrivals > MAX_OFF_RING_ARRIVALS) {
                        coverage.resolve(point, OrbitCoverage.State.BLOCKED);
                        blockedWaypoints++;
                        if (blockedWaypoints >= MAX_BLOCKED) {
                            return fail(blockedReason(coverage.blocked()));
                        }
                        nextWaypoint(c);
                        return TaskStatus.RUNNING;
                    }
                    restartAttempt(c);
                    return TaskStatus.RUNNING;
                }
                coverage.resolve(point, attempt == 0 ? OrbitCoverage.State.REACHED : OrbitCoverage.State.BYPASSED);
                nextWaypoint(c);
            }
            case STUCK -> {
                if (!nextAttempt(c)) {
                    coverage.resolve(point, OrbitCoverage.State.BLOCKED);
                    blockedWaypoints++;
                    if (blockedWaypoints >= MAX_BLOCKED) {
                        return fail(blockedReason(coverage.blocked()));
                    }
                    nextWaypoint(c);
                }
            }
            default -> {}
        }
        return TaskStatus.RUNNING;
    }

    private Vec3 waypointAt(TaskContext c) {
        double angle = startAngle + 2.0 * Math.PI * (point + 1) / POINTS_PER_LAP;
        double wanted = attemptRadius();
        return new Vec3(anchor.x + Math.cos(angle) * wanted, c.companion().getY(), anchor.z + Math.sin(angle) * wanted);
    }

    /** The primary arc, then one wider and one narrower arc around the same centre. */
    private double attemptRadius() {
        return switch (attempt) {
            case 0 -> radius;
            case 1 -> radius + OUTWARD_BYPASS;
            default -> Math.max(1.0, radius - INWARD_BYPASS);
        };
    }

    private boolean nextAttempt(TaskContext c) {
        if (attempt >= 2) return false;
        attempt++;
        restartAttempt(c);
        return true;
    }

    private void restartAttempt(TaskContext c) {
        mover.reset(c);
    }

    private void nextWaypoint(TaskContext c) {
        point++;
        attempt = 0;
        jumps = 0;
        offRingArrivals = 0;
        if (point % POINTS_PER_LAP == 0) {
            blockedWaypoints = 0;
        }
        mover.reset(c);
        if (point < coverage.total()) {
            c.companion().body().holdLook(anchorLook, LOOK_TICKS);
        }
    }

    /**
     * A point counts only when its own cell is open and the body stands at it. Arrival near a blocked cell is exactly
     * the phantom progress a moving or vanished centre can produce, so the cell is checked rather than a radius band.
     */
    private boolean reachedWaypoint(TaskContext c, Vec3 waypoint) {
        BlockPos cell = BlockPos.containing(waypoint.x, waypoint.y, waypoint.z);
        if (!c.level().getBlockState(cell).getCollisionShape(c.level(), cell).isEmpty()) return false;
        double horizontal =
                Math.hypot(c.companion().getX() - waypoint.x, c.companion().getZ() - waypoint.z);
        return horizontal <= WAYPOINT_SLACK && Math.abs(c.companion().getY() - waypoint.y) <= 1.0;
    }

    private TaskStatus finish() {
        if (coverage.blocked() == 0 && coverage.verified() == coverage.total()) {
            summary = OrbitReport.success(centerLabel, laps, coverage);
            return TaskStatus.SUCCESS;
        }
        return fail(blockedReason(coverage.blocked()));
    }

    private TaskStatus fail(String reason) {
        summary = OrbitReport.partial(centerLabel, laps, coverage, reason);
        return TaskStatus.FAILED;
    }

    private static String blockedReason(int blocked) {
        return blocked == 1
                ? "one waypoint could not be reached safely"
                : blocked + " waypoints could not be reached safely";
    }

    @Override
    public void stop(TaskContext c) {
        mover.stop(c);
        c.companion().body().releaseLook();
    }

    @Override
    public void onTerminal(TaskContext c) {
        mover.stop(c);
        c.companion().body().releaseLook();
    }

    @Override
    public String describe() {
        return "walking around the " + centerLabel + " at " + radius + " blocks";
    }

    @Override
    public String resultSummary() {
        return summary;
    }

    @Override
    public String debugStatus() {
        return "waypoints=" + coverage.verified() + "/" + coverage.total() + " blocked=" + coverage.blocked()
                + " attempt=" + attempt;
    }
}
