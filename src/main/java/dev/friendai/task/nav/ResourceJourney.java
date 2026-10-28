package dev.friendai.task.nav;

import dev.friendai.core.FriendaiServerState;
import dev.friendai.entity.ExpeditionChunkHorizon;
import dev.friendai.knowledge.search.SearchSession;
import dev.friendai.knowledge.travel.TrailGraph;
import dev.friendai.pathing.GoalPoint;
import dev.friendai.pathing.Pos;
import dev.friendai.task.TaskContext;
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
        this.dimension = Objects.requireNonNull(dimension);
        this.target = Objects.requireNonNull(target);
        this.session = session;
        this.trails = Objects.requireNonNull(trails);
        this.requestChunks = requestChunks;
        this.navigator = new Navigator(context.companion(), false, null, null, context.serverSettings());
        Pos start = current(context);
        executedLeg.add(start);
        legStartedTick = logicalTick(context);
    }

    public State tick(TaskContext context) {
        if (stopped) return State.CANCELLED;
        ServerLevel level = context.level();
        if (!dimension.equals(level.dimension().identifier().toString())) return State.BLOCKED;
        Pos current = current(context);
        if (!executedLeg.get(executedLeg.size() - 1).equals(current)) executedLeg.add(current);
        if (horizontal(current, target) <= 1 && Math.abs(current.y() - target.y()) <= 2) return State.ARRIVED;
        if (waypoint == null || horizontal(current, waypoint) <= 3 && Math.abs(current.y() - waypoint.y()) <= 2) {
            if (horizontal(current, target) <= 16 && Math.abs(current.y() - target.y()) <= 16) {
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
            navigator.setGoal(new GoalPoint(waypoint.x() + 0.5, waypoint.y(), waypoint.z() + 0.5, 1.0, 2));
            if (!requestChunks && level.getChunkSource().getChunkNow(waypoint.x() >> 4, waypoint.z() >> 4) == null)
                return State.BLOCKED;
            if (requestChunks && context.companion().ownerUuid() != null) {
                lease = FriendaiServerState.get(level.getServer())
                        .chunkLoader()
                        .expeditionHorizon()
                        .request(
                                context.companion().ownerUuid(),
                                level,
                                new ChunkPos(waypoint.x() >> 4, waypoint.z() >> 4));
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
                    return State.BLOCKED;
                }
            } else {
                return State.WAITING_FOR_CHUNK;
            }
        }
        Navigator.State state = navigator.tick(context);
        if (state == Navigator.State.UNREACHABLE) return State.BLOCKED;
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
            return horizontal(current(context), target) <= 1
                            && Math.abs(current(context).y() - target.y()) <= 2
                    ? State.ARRIVED
                    : State.WORKING;
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
    }

    public SearchSession session() {
        return session;
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
