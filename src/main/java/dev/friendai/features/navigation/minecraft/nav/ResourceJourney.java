package dev.friendai.features.navigation.minecraft.nav;

import dev.friendai.entity.ExpeditionChunkHorizon;
import dev.friendai.features.knowledge.search.SearchSession;
import dev.friendai.features.knowledge.travel.TrailGraph;
import dev.friendai.foundation.spatial.GoalPoint;
import dev.friendai.foundation.spatial.PathingConfig;
import dev.friendai.foundation.spatial.Pos;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.world.block.ScaffoldCleanupTask;
import dev.friendai.world.movement.Navigator;
import dev.friendai.world.placement.PlacementLedger;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

/** Moves between short local legs along previously executed trail edges. */
public final class ResourceJourney {
    public enum State {
        WORKING,
        ARRIVED,
        WAITING_FOR_CHUNK,
        BLOCKED,
        CANCELLED
    }

    private final String dimension;
    private final Pos target;
    private final TrailGraph trails;
    private final SearchSession session;
    private final boolean requestChunks;
    private final int returnSupports;
    private final boolean returnTrip;
    private final PlacementLedger placements = new PlacementLedger();
    private List<Pos> verifiedRoute = List.of();
    private int routeIndex = -1;
    private boolean alternateTried;
    private ScaffoldCleanupTask cleanup;
    private State afterCleanup;
    private Navigator navigator;
    private Pos waypoint;
    private boolean stopped;
    private final List<Pos> executedLeg = new ArrayList<>();
    private long legStartedTick;
    private ExpeditionChunkHorizon.Lease lease;

    public ResourceJourney(
            TaskContext context, String dimension, Pos target, SearchSession session, TrailGraph trails) {
        this(context, dimension, target, session, trails, true);
    }

    public ResourceJourney(
            TaskContext context,
            String dimension,
            Pos target,
            SearchSession session,
            TrailGraph trails,
            boolean requestChunks) {
        this(context, dimension, target, session, trails, requestChunks, List.of(), 0);
    }

    /**
     * Uses a freshly verified mining route back to its anchor, with one bounded Navigator alternative if it changed.
     * The return leg can spend only the support count reserved by the active search.
     */
    public ResourceJourney(
            TaskContext context,
            String dimension,
            Pos target,
            SearchSession session,
            TrailGraph trails,
            boolean requestChunks,
            List<Pos> verifiedRoute,
            int returnSupports) {
        this.dimension = Objects.requireNonNull(dimension);
        this.target = Objects.requireNonNull(target);
        this.session = session;
        this.trails = Objects.requireNonNull(trails);
        this.requestChunks = requestChunks;
        this.returnSupports = Math.max(0, returnSupports);
        Pos start = current(context);
        boolean routeIsFresh =
                dimension.equals(context.level().dimension().identifier().toString())
                        && routeIsFresh(context, verifiedRoute, start, target);
        this.returnTrip = verifiedRoute != null && !verifiedRoute.isEmpty();
        this.verifiedRoute = routeIsFresh ? List.copyOf(verifiedRoute) : List.of();
        this.alternateTried = this.returnTrip && !routeIsFresh;
        this.routeIndex = this.verifiedRoute.size() - 2;
        this.navigator = this.returnTrip ? returnNavigator(context) : new Navigator(context, false, null, null);
        executedLeg.add(start);
        legStartedTick = logicalTick(context);
    }

    public State tick(TaskContext context) {
        if (stopped) return State.CANCELLED;
        ServerLevel level = context.level();
        if (!dimension.equals(level.dimension().identifier().toString())) return State.BLOCKED;
        if (cleanup != null) return tickCleanup(context);
        Pos current = current(context);
        if (!executedLeg.get(executedLeg.size() - 1).equals(current)) executedLeg.add(current);
        if (atTarget(context)) return beginCleanup(context, State.ARRIVED);
        if (waypoint == null
                || (!returnTrip && horizontal(current, waypoint) <= 3 && Math.abs(current.y() - waypoint.y()) <= 2)) {
            if (routeIndex >= 0 && !verifiedRoute.isEmpty()) {
                waypoint = verifiedRoute.get(routeIndex--);
            } else if (horizontal(current, target) <= 16 && Math.abs(current.y() - target.y()) <= 16) {
                waypoint = target;
            } else {
                List<Pos> route = trails.route(dimension, current, target);
                if (route.isEmpty()) {
                    // A remembered approach is evidence for the destination, not a remembered path.
                    // Advance toward it in short local legs and let Navigator validate each leg.
                    waypoint = localWaypoint(current, target, 16.0);
                } else {
                    waypoint = route.size() > 1 ? route.get(1) : target;
                    if (distance(current, waypoint) > 16.0) return State.BLOCKED;
                }
            }
            navigator.setGoal(
                    new GoalPoint(waypoint.x() + 0.5, waypoint.y(), waypoint.z() + 0.5, 1.0, returnTrip ? 0.75 : 2));
            if (!requestChunks && level.getChunkSource().getChunkNow(waypoint.x() >> 4, waypoint.z() >> 4) == null) {
                return tryAlternate(context) ? State.WORKING : State.BLOCKED;
            }
            if (requestChunks && context.companion().ownerUuid() != null) {
                lease = context.requestExpeditionChunk(new ChunkPos(waypoint.x() >> 4, waypoint.z() >> 4));
            }
        }
        if (lease != null && !lease.ready()) {
            if (lease.expired()) {
                if (level.getChunkSource().getChunkNow(waypoint.x() >> 4, waypoint.z() >> 4) != null) {
                    lease.close();
                    lease = null;
                } else {
                    lease.close();
                    lease = null;
                    return tryAlternate(context) ? State.WORKING : State.BLOCKED;
                }
            } else {
                return State.WAITING_FOR_CHUNK;
            }
        }
        Navigator.State state = navigator.tick(context);
        if (state == Navigator.State.UNREACHABLE) {
            if (tryAlternate(context)) return State.WORKING;
            return beginCleanup(context, State.BLOCKED);
        }
        if (state == Navigator.State.ARRIVED) {
            if (lease != null) {
                lease.close();
                lease = null;
            }
            if (executedLeg.size() > 1)
                trails.recordSegment(
                        dimension,
                        executedLeg,
                        Math.max(0, logicalTick(context) - legStartedTick),
                        session != null && session.snapshot().phase() == SearchSession.Phase.RETURN);
            executedLeg.clear();
            executedLeg.add(current(context));
            waypoint = null;
            legStartedTick = logicalTick(context);
            return atTarget(context) ? beginCleanup(context, State.ARRIVED) : State.WORKING;
        }
        return State.WORKING;
    }

    public void stop(TaskContext context) {
        if (stopped) return;
        stopped = true;
        if (lease != null) {
            lease.close();
            lease = null;
        }
        if (navigator != null) navigator.stop(context);
        if (cleanup != null) cleanup.stop(context);
    }

    /** A cancellation cleanup for a support block placed by this return route. */
    public Task cleanupAfterCancel() {
        return placements.isEmpty() ? null : new ScaffoldCleanupTask(placements.positions());
    }

    public SearchSession session() {
        return session;
    }

    private State beginCleanup(TaskContext context, State result) {
        if (placements.isEmpty()) return result;
        afterCleanup = result;
        cleanup = new ScaffoldCleanupTask(placements.positions());
        cleanup.start(context);
        if (navigator != null) navigator.stop(context);
        return State.WORKING;
    }

    private Navigator returnNavigator(TaskContext context) {
        boolean mayPlace = returnSupports > 0 && context.pathingConfig().allowPlace();
        PathingConfig routeConfig = context.pathingConfig().withActions(false, mayPlace);
        return new Navigator(context, mayPlace, routeConfig, placements, true, returnSupports);
    }

    private boolean tryAlternate(TaskContext context) {
        if (!returnTrip || alternateTried) return false;
        alternateTried = true;
        if (navigator != null) navigator.stop(context);
        verifiedRoute = List.of();
        routeIndex = -1;
        waypoint = null;
        navigator = returnNavigator(context);
        return true;
    }

    private State tickCleanup(TaskContext context) {
        TaskStatus status = cleanup.tick(context);
        if (status == TaskStatus.RUNNING) return State.WORKING;
        cleanup.stop(context);
        cleanup = null;
        State result = status == TaskStatus.SUCCESS ? afterCleanup : State.BLOCKED;
        afterCleanup = null;
        return result;
    }

    private static boolean routeIsFresh(TaskContext context, List<Pos> route, Pos start, Pos target) {
        if (route == null || route.size() < 2 || !near(route.getFirst(), target) || !near(route.getLast(), start)) {
            return false;
        }
        ServerLevel level = context.level();
        for (Pos pos : route) {
            if (level.getChunkSource().getChunkNow(pos.x() >> 4, pos.z() >> 4) == null) return false;
        }
        return true;
    }

    private static boolean near(Pos left, Pos right) {
        return horizontal(left, right) <= 1 && Math.abs(left.y() - right.y()) <= 1;
    }

    private boolean atTarget(TaskContext context) {
        if (returnTrip) {
            var companion = context.companion();
            double dx = companion.getX() - (target.x() + 0.5);
            double dz = companion.getZ() - (target.z() + 0.5);
            return Math.sqrt(dx * dx + dz * dz) <= 1.0 && Math.abs(companion.getY() - target.y()) <= 0.75;
        }
        Pos current = current(context);
        return horizontal(current, target) <= 1 && Math.abs(current.y() - target.y()) <= 2;
    }

    private static double horizontal(Pos a, Pos b) {
        return Math.hypot((double) a.x() - b.x(), (double) a.z() - b.z());
    }

    private static double distance(Pos a, Pos b) {
        double dx = (double) a.x() - b.x(), dy = (double) a.y() - b.y(), dz = (double) a.z() - b.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static Pos localWaypoint(Pos from, Pos to, double maxLength) {
        double distance = distance(from, to);
        if (distance <= maxLength) return to;
        double fraction = maxLength / distance;
        int x = (int) Math.round(from.x() + (to.x() - (double) from.x()) * fraction);
        int y = (int) Math.round(from.y() + (to.y() - (double) from.y()) * fraction);
        int z = (int) Math.round(from.z() + (to.z() - (double) from.z()) * fraction);
        if (x == from.x() && y == from.y() && z == from.z()) return to;
        return new Pos(x, y, z);
    }

    private static Pos current(TaskContext context) {
        var pos = context.companion().blockPosition();
        return new Pos(pos.getX(), pos.getY(), pos.getZ());
    }

    private static long logicalTick(TaskContext context) {
        return context.level().getServer().overworld().getGameTime();
    }
}
