package dev.friendai.task.nav;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.pathing.CostModel;
import dev.friendai.pathing.GoalPoint;
import dev.friendai.pathing.GoalReach;
import dev.friendai.pathing.Node;
import dev.friendai.pathing.Path;
import dev.friendai.pathing.PathGoal;
import dev.friendai.pathing.PathSearch;
import dev.friendai.pathing.PathStep;
import dev.friendai.pathing.PathingConfig;
import dev.friendai.pathing.Pos;
import dev.friendai.pathing.SearchOptions;
import dev.friendai.pathing.SearchResult;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskSettings;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * Gets one companion to a {@link PathGoal}: searches with a per-tick time budget, executes the route, re-plans when a
 * step fails, and falls back to vanilla navigation when searching takes too long.
 *
 * <p>A new goal does not stop the companion: it keeps walking the old route while the new search runs, and switches
 * when the search returns a route. Nothing here touches the world off the server thread.
 */
public final class Navigator {
    public enum State { SEARCHING, MOVING, GATHERING, ARRIVED, UNREACHABLE }

    /** Re-plans without getting closer than this many ticks of cost are given up after three tries. */
    private static final int REPLAN_LIMIT = 3;
    /** Total search time for one goal before vanilla navigation takes over. */
    private static final long FALLBACK_NANOS = 200_000_000L;
    /** How many times one goal may send the companion out to gather before giving up. */
    private static final int GATHER_ROUNDS = 2;
    private static final double PROGRESS = 1.0;
    private static final int FALLBACK_REPATH_TICKS = 10;
    private static final int FALLBACK_STUCK_TICKS = 100;
    private static final double FALLBACK_MIN_PROGRESS = 0.5;

    private final CompanionEntity companion;
    /** Whether a route may place blocks. Approaching a block to act on it walks and digs, but never builds. */
    private final boolean mayPlace;
    private PathGoal goal;
    private PathSearch search;
    private Path plan;
    private PathExecutor executor;
    private State state = State.SEARCHING;
    private long searchNanos;
    private int failedPlans;
    private double bestHeuristic = Double.POSITIVE_INFINITY;
    private boolean fallback;
    private int fallbackTicks;
    private int fallbackCheckpoint;
    private double fallbackDistance;
    /** Whether the running search is the material probe (2.5), which ignores the inventory. */
    private boolean probing;
    /** How many blocks the inventory held when the probe started; the probe path may want more. */
    private int availableAtProbe;
    private MaterialGatherer gatherer;
    private int gatherRounds;

    public Navigator(CompanionEntity companion) {
        this(companion, true);
    }

    /**
     * @param mayPlace whether the route may pillar or bridge. An approach to a block that is only there to be used
     *     (a chest, a block a task places or breaks) keeps it false: the task owns the terrain and the inventory it
     *     brought for its own work, and a pillar of scaffold blocks would spend both.
     */
    public Navigator(CompanionEntity companion, boolean mayPlace) {
        this.companion = companion;
        this.mayPlace = mayPlace;
    }

    public State state() {
        return state;
    }

    public PathGoal goal() {
        return goal;
    }

    /**
     * Aims at a new goal. While the new search runs the companion keeps walking the route it already had; the route
     * is replaced when a search finds a way to the new goal.
     */
    public void setGoal(PathGoal goal) {
        if (goal == null || goal.equals(this.goal)
                && (search != null || executor != null || state == State.SEARCHING || state == State.GATHERING)) {
            return;
        }
        if (gatherer != null) {
            gatherer.stop(new TaskContext(companion, (ServerLevel) companion.level()));
            gatherer = null;
        }
        this.goal = goal;
        searchNanos = 0;
        failedPlans = 0;
        bestHeuristic = goal.heuristic(node());
        fallback = false;
        probing = false;
        gatherRounds = 0;
        startSearch();
        if (executor == null) {
            state = State.SEARCHING;
        }
    }

    public State tick(TaskContext c) {
        if (state == State.ARRIVED || state == State.UNREACHABLE) {
            return state;
        }
        if (fallback) {
            return tickFallback(c);
        }
        if (search != null) {
            stepSearch(c);
        }
        if (executor != null) {
            return tickExecutor(c);
        }
        if (state == State.GATHERING) {
            tickGathering(c);
            return state;
        }
        if (state != State.ARRIVED && state != State.UNREACHABLE) {
            state = State.SEARCHING;
        }
        return state;
    }

    /** Stops the route and navigation, clearing any crack overlay. */
    public void stop(TaskContext c) {
        if (executor != null) {
            executor.stop(c);
            executor = null;
        }
        if (gatherer != null) {
            gatherer.stop(c);
            gatherer = null;
        }
        probing = false;
        search = null;
        companion.getNavigation().stop();
    }

    private void tickGathering(TaskContext c) {
        if (gatherer == null) {
            startSearch();
            state = State.SEARCHING;
            return;
        }
        if (gatherer.tick(c) == MaterialGatherer.State.WORKING) {
            state = State.GATHERING;
            return;
        }
        gatherer = null;
        gatherRounds++;
        startSearch();
        state = State.SEARCHING;
    }

    /** Searches once more as if the companion carried every block it needs, to learn how many it is short of. */
    private void beginProbe() {
        PathingConfig config = TaskSettings.pathing;
        LevelCellView view = new LevelCellView(companion, config, CostModel.DEFAULTS);
        availableAtProbe = new MaterialPicker(config).count(companion.inventory());
        probing = true;
        search = new PathSearch(view, CostModel.DEFAULTS, goal, node(),
                new SearchOptions(config.maxNodes(), 64, Integer.MAX_VALUE, 1));
        state = State.SEARCHING;
    }

    private void startGathering(TaskContext c, Path path) {
        if (path == null || path.steps().isEmpty() || path.places() <= availableAtProbe) {
            state = State.UNREACHABLE;
            return;
        }
        int missing = path.places() - availableAtProbe;
        // A few spare blocks: an extra bridge cell or a pillar is cheaper to gather now than to walk back for.
        int needed = Math.min(missing + 4, TaskSettings.pathing.maxGatherBlocks());
        gatherer = new MaterialGatherer(needed, avoidCells(path));
        state = State.GATHERING;
    }

    /** The cells a route walks or builds through: gathering must not take them away from it. */
    private static Set<BlockPos> avoidCells(Path path) {
        Set<BlockPos> cells = new HashSet<>();
        for (PathStep step : path.steps()) {
            cells.add(blockPos(step.from()));
            cells.add(blockPos(step.to()));
            for (Pos pos : step.places()) {
                cells.add(blockPos(pos));
            }
        }
        return cells;
    }

    private static BlockPos blockPos(Pos pos) {
        return new BlockPos(pos.x(), pos.y(), pos.z());
    }

    private static BlockPos blockPos(Node node) {
        return new BlockPos(node.x(), node.y(), node.z());
    }

    private void stepSearch(TaskContext c) {
        long budget = TaskSettings.pathing.searchBudgetMs() * 1_000_000L;
        long begin = System.nanoTime();
        SearchResult result = search.step(budget, System::nanoTime);
        searchNanos += System.nanoTime() - begin;
        if (result.status() != SearchResult.Status.RUNNING) {
            handleSearchResult(c, result);
        } else if (searchNanos > FALLBACK_NANOS) {
            startFallback(c);
        }
    }

    private void handleSearchResult(TaskContext c, SearchResult result) {
        Path path = result.path();
        StringBuilder line = new StringBuilder("path ").append(describe(goal())).append(": ").append(result.status());
        line.append(", ").append(search.expanded()).append(" nodes, ").append(searchNanos / 1_000_000L).append(" ms");
        if (path != null) {
            line.append(", cost ").append(Math.round(path.cost()))
                    .append(", breaks ").append(path.breaks())
                    .append(", places ").append(path.places())
                    .append(path.partial() ? ", partial" : "");
        }
        boolean probed = probing;
        probing = false;
        if (probed) {
            line.append(", material probe");
        }
        companion.taskRunner().debug(line.toString());
        search = null;
        if (result.status() == SearchResult.Status.FOUND && path != null && path.steps().isEmpty()) {
            // The start node already is the goal: there is nothing to walk.
            state = State.ARRIVED;
            return;
        }
        if (probed) {
            startGathering(c, path);
            return;
        }
        if (result.status() == SearchResult.Status.NO_PATH || path == null || path.steps().isEmpty()) {
            if (!mayPlace || !TaskSettings.pathing.allowPlace() || gatherRounds >= GATHER_ROUNDS) {
                state = State.UNREACHABLE;
                return;
            }
            // A missing route may mean missing material: ask what it would take with unlimited blocks to place.
            beginProbe();
            return;
        }
        if (executor != null) {
            executor.stop(c);
        }
        plan = path;
        executor = new PathExecutor(path, new MaterialPicker(TaskSettings.pathing));
        state = State.MOVING;
    }

    private State tickExecutor(TaskContext c) {
        PathExecutor.State result = executor.tick(c);
        if (result == PathExecutor.State.RUNNING) {
            state = State.MOVING;
            return state;
        }
        if (result == PathExecutor.State.FAILED) {
            executor = null;
            notePlanFailure();
            return afterFailure();
        }
        executor.stop(c);
        executor = null;
        if (plan != null && plan.partial()) {
            startSearch();
            state = State.SEARCHING;
            return state;
        }
        if (goalHolds()) {
            companion.getNavigation().stop();
            state = State.ARRIVED;
            return state;
        }
        notePlanFailure();
        return afterFailure();
    }

    private State afterFailure() {
        if (failedPlans >= REPLAN_LIMIT) {
            state = State.UNREACHABLE;
            return state;
        }
        startSearch();
        state = State.SEARCHING;
        return state;
    }

    private void notePlanFailure() {
        double heuristic = goal.heuristic(node());
        if (heuristic < bestHeuristic - PROGRESS) {
            bestHeuristic = heuristic;
            failedPlans = 1;
        } else {
            failedPlans++;
        }
    }

    private void startSearch() {
        PathingConfig config = TaskSettings.pathing;
        LevelCellView view = new LevelCellView(companion, config, CostModel.DEFAULTS);
        MaterialPicker materials = new MaterialPicker(config);
        int placeable = mayPlace ? materials.count(companion.inventory()) : 0;
        double price = mayPlace ? materials.cheapestPrice(companion.inventory()) : 0.0;
        SearchOptions options = new SearchOptions(config.maxNodes(), 64, placeable, price);
        search = new PathSearch(view, CostModel.DEFAULTS, goal, node(), options);
    }

    // --- vanilla navigation fallback ----------------------------------------------------------------------------

    private void startFallback(TaskContext c) {
        if (executor != null) {
            executor.stop(c);
            executor = null;
        }
        search = null;
        fallback = true;
        fallbackTicks = 0;
        fallbackCheckpoint = 0;
        fallbackDistance = Double.POSITIVE_INFINITY;
        companion.getNavigation().stop();
        companion.taskRunner().debug("path " + describe(goal)
                + ": searching took too long, falling back to vanilla navigation");
    }

    private State tickFallback(TaskContext c) {
        Vec3 point = fallbackPoint();
        if (point == null) {
            state = State.UNREACHABLE;
            return state;
        }
        if (goalHolds()) {
            companion.getNavigation().stop();
            state = State.ARRIVED;
            return state;
        }
        double distance = companion.position().distanceTo(point);
        if (fallbackTicks % FALLBACK_REPATH_TICKS == 0) {
            companion.getNavigation().moveTo(point.x, point.y, point.z, 1.0);
        }
        if (fallbackCheckpoint == 0) {
            fallbackDistance = distance;
        }
        if (++fallbackCheckpoint >= FALLBACK_STUCK_TICKS) {
            if (fallbackDistance - distance < FALLBACK_MIN_PROGRESS) {
                state = State.UNREACHABLE;
                return state;
            }
            fallbackCheckpoint = 0;
        }
        fallbackTicks++;
        state = State.MOVING;
        return state;
    }

    private Vec3 fallbackPoint() {
        if (goal instanceof GoalPoint point) {
            return new Vec3(point.x(), point.y(), point.z());
        }
        if (goal instanceof GoalReach reach) {
            Vec3 here = companion.position();
            Vec3 best = null;
            double bestDistance = Double.POSITIVE_INFINITY;
            for (dev.friendai.pathing.Pos target : reach.targets()) {
                Vec3 center = new Vec3(target.x() + 0.5, target.y() + 0.5, target.z() + 0.5);
                double distance = here.distanceToSqr(center);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = center;
                }
            }
            return best;
        }
        return null;
    }

    private boolean goalHolds() {
        LevelCellView view = new LevelCellView(companion, TaskSettings.pathing, CostModel.DEFAULTS);
        return goal.isGoal(node(), view);
    }

    private Node node() {
        var pos = companion.blockPosition();
        return new Node(pos.getX(), pos.getY(), pos.getZ());
    }

    private static String describe(PathGoal goal) {
        if (goal instanceof GoalPoint point) {
            return "point " + (int) Math.floor(point.x()) + " " + (int) Math.floor(point.y()) + " "
                    + (int) Math.floor(point.z());
        }
        if (goal instanceof GoalReach reach) {
            return reach.targets().size() == 1 ? "one cell" : reach.targets().size() + " cells";
        }
        return "goal";
    }
}
