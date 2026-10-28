package dev.friendai.task.nav;

import dev.friendai.creative.CreativeAction;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.movement.FlightSearch;
import dev.friendai.pathing.Pos;
import dev.friendai.task.TaskContext;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Collision-aware flight over loaded, body-clear cells with bounded incremental search work. */
public final class FlightNavigator {
    public enum State {
        SEARCHING,
        MOVING,
        ARRIVED,
        UNREACHABLE
    }

    public static final double SPEED = 0.30;
    public static final double ARRIVAL_TOLERANCE = 1.0;
    public static final int EXPANSIONS_PER_TICK = 512;
    public static final int MAX_SEARCH_EXPANSIONS = 16_384;
    private static final int MAX_REPLANS = 3;
    private static final double WAYPOINT_TOLERANCE = 0.16;

    private FlightSearch search;
    private List<Pos> route;
    private int routeIndex;
    private Vec3 target;
    private State state = State.SEARCHING;
    private int replans;
    private int blockedTicks;
    private FlightSearch.Status lastSearchStatus;
    private int lastSearchExpanded;
    private String lastMovementBlock = "";

    public State tick(TaskContext context, Vec3 requested) {
        return tick(context, requested, false);
    }

    /** {@code passiveFollow} is only used for the system-owned default follow behavior. */
    public State tick(TaskContext context, Vec3 requested, boolean passiveFollow) {
        CompanionEntity companion = context.companion();
        if (!companion.creativeMode()
                || !passiveFollow && !context.canUseCreativeAction(CreativeAction.FLIGHT)
                || !finite(requested)) {
            stop(context);
            return State.UNREACHABLE;
        }
        companion.beginCreativeFlight();
        companion.getNavigation().stop();
        companion.setPathing(true);
        companion.setDeltaMovement(Vec3.ZERO);
        if (target == null || target.distanceToSqr(requested) > 0.25) {
            target = requested;
            beginSearch(companion);
        }
        if (companion.position().distanceToSqr(target) <= ARRIVAL_TOLERANCE * ARRIVAL_TOLERANCE) {
            state = State.ARRIVED;
            stop(context);
            return state;
        }
        if (search != null) {
            FlightSearch.Progress progress = search.step(EXPANSIONS_PER_TICK);
            if (progress.status() == FlightSearch.Status.RUNNING) {
                state = State.SEARCHING;
                return state;
            }
            if (progress.status() != FlightSearch.Status.FOUND) {
                lastSearchStatus = progress.status();
                lastSearchExpanded = progress.totalExpanded();
                return unreachable(context);
            }
            lastSearchStatus = progress.status();
            lastSearchExpanded = progress.totalExpanded();
            route = search.path();
            routeIndex = Math.min(1, route.size());
            search = null;
        }
        if (route == null) {
            return unreachable(context);
        }
        if (routeIndex < route.size()) {
            Vec3 waypoint = feetCenter(route.get(routeIndex));
            if (companion.position().distanceToSqr(waypoint) <= WAYPOINT_TOLERANCE * WAYPOINT_TOLERANCE) {
                routeIndex++;
                if (routeIndex < route.size()) waypoint = feetCenter(route.get(routeIndex));
            }
            if (routeIndex < route.size() && !moveToward(context, waypoint)) {
                if (++blockedTicks >= 3) {
                    if (++replans > MAX_REPLANS) return unreachable(context);
                    beginSearch(companion);
                }
                state = State.MOVING;
                return state;
            }
        }
        // Keep each movement segment inside the checked A* path. A direct shortcut here can cut through the very
        // wall the route went around and repeatedly cancel progress at the first waypoint.
        if (routeIndex < route.size()) {
            state = State.MOVING;
            return state;
        }
        if (companion.position().distanceToSqr(target) <= ARRIVAL_TOLERANCE * ARRIVAL_TOLERANCE) {
            state = State.ARRIVED;
            stop(context);
            return state;
        }
        Vec3 finalCell = route.isEmpty() ? target : feetCenter(route.getLast());
        Vec3 aim = companion.position().distanceToSqr(target) <= finalCell.distanceToSqr(target) ? target : finalCell;
        if (companion.position().distanceToSqr(aim) > WAYPOINT_TOLERANCE * WAYPOINT_TOLERANCE) {
            if (!moveToward(context, aim) && ++blockedTicks >= 3) {
                if (++replans > MAX_REPLANS) return unreachable(context);
                beginSearch(companion);
            }
        }
        state = State.MOVING;
        return state;
    }

    public State state() {
        return state;
    }

    public String diagnostic() {
        if (lastSearchStatus == FlightSearch.Status.NO_PATH) return "no loaded collision-safe route";
        if (lastSearchStatus == FlightSearch.Status.LIMIT) return "flight search reached its node budget";
        if (replans > MAX_REPLANS)
            return "movement remained blocked after bounded replanning (" + lastMovementBlock + ")";
        return "flight route is unavailable";
    }

    public int lastSearchExpanded() {
        return lastSearchExpanded;
    }

    public String debugStatus() {
        return state + ", route " + routeIndex + "/" + (route == null ? 0 : route.size())
                + ", search=" + (search == null ? "none" : search.status() + "/" + search.expanded())
                + ", blockedTicks=" + blockedTicks + ", replans=" + replans
                + (lastMovementBlock.isEmpty() ? "" : ", lastBlock=" + lastMovementBlock);
    }

    public void stop(TaskContext context) {
        stop(context, state == State.ARRIVED);
    }

    /** Stops movement, optionally keeping no-gravity enabled because the caller accepted this as its destination. */
    public void stop(TaskContext context, boolean keepHovering) {
        search = null;
        route = null;
        routeIndex = 0;
        target = null;
        blockedTicks = 0;
        context.companion().setPathing(false);
        context.companion().endCreativeFlight(keepHovering);
    }

    private boolean moveToward(TaskContext context, Vec3 aim) {
        CompanionEntity companion = context.companion();
        Vec3 offset = aim.subtract(companion.position());
        double distance = offset.length();
        if (distance <= WAYPOINT_TOLERANCE) return true;
        Vec3 movement = offset.scale(Math.min(SPEED, distance) / distance);
        AABB swept = companion.getBoundingBox().expandTowards(movement);
        if (!canOccupy(context.level(), companion, swept)) {
            lastMovementBlock = "swept body collides with a block or entity";
            return false;
        }
        Vec3 before = companion.position();
        // The body-sized swept AABB was checked above. Place this small, validated step directly so ground-mob
        // collision resolution cannot clip a vertical step along a wall and leave the route oscillating in place.
        companion.setPos(before.x + movement.x, before.y + movement.y, before.z + movement.z);
        companion.setDeltaMovement(Vec3.ZERO);
        companion.fallDistance = 0;
        double moved = before.distanceTo(companion.position());
        if (moved < movement.length() * 0.5) {
            lastMovementBlock = "Minecraft collision resolution clipped movement";
            return false;
        }
        blockedTicks = 0;
        return true;
    }

    private void beginSearch(CompanionEntity companion) {
        Pos start = cell(companion.position());
        Pos goal = cell(target);
        ServerLevel level = (ServerLevel) companion.level();
        search = new FlightSearch(start, goal, pos -> freeCell(level, companion, pos), MAX_SEARCH_EXPANSIONS);
        route = null;
        routeIndex = 0;
        blockedTicks = 0;
        state = State.SEARCHING;
        lastSearchStatus = null;
        lastSearchExpanded = 0;
    }

    private State unreachable(TaskContext context) {
        state = State.UNREACHABLE;
        stop(context);
        state = State.UNREACHABLE;
        return state;
    }

    private static boolean freeCell(ServerLevel level, CompanionEntity companion, Pos pos) {
        if (pos.y() < level.getMinY() || pos.y() > level.getMaxY()) return false;
        Vec3 feet = feetCenter(pos);
        AABB box = companion.getBoundingBox().move(feet.subtract(companion.position()));
        return canOccupy(level, companion, box);
    }

    private static boolean canOccupy(ServerLevel level, CompanionEntity companion, AABB box) {
        if (box.minY < level.getMinY() || box.maxY > level.getMaxY() + 1.0) return false;
        var border = level.getWorldBorder();
        if (box.minX < border.getMinX()
                || box.maxX > border.getMaxX()
                || box.minZ < border.getMinZ()
                || box.maxZ > border.getMaxZ()) return false;
        int minX = BlockPos.containing(box.minX, box.minY, box.minZ).getX();
        int maxX = BlockPos.containing(box.maxX, box.maxY, box.maxZ).getX();
        int minY = BlockPos.containing(box.minX, box.minY, box.minZ).getY();
        int maxY = BlockPos.containing(box.maxX, box.maxY, box.maxZ).getY();
        int minZ = BlockPos.containing(box.minX, box.minY, box.minZ).getZ();
        int maxZ = BlockPos.containing(box.maxX, box.maxY, box.maxZ).getZ();
        for (int x : new int[] {minX, maxX}) {
            for (int z : new int[] {minZ, maxZ}) {
                if (!level.hasChunkAt(x, z)) return false;
            }
        }
        return level.noCollision(companion, box);
    }

    private static Vec3 feetCenter(Pos pos) {
        return new Vec3(pos.x() + 0.5, pos.y(), pos.z() + 0.5);
    }

    private static Pos cell(Vec3 position) {
        return new Pos((int) Math.floor(position.x), (int) Math.floor(position.y), (int) Math.floor(position.z));
    }

    private static boolean finite(Vec3 pos) {
        return pos != null && Double.isFinite(pos.x) && Double.isFinite(pos.y) && Double.isFinite(pos.z);
    }
}
