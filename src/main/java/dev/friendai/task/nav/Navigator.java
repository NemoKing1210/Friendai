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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

/**
 * Gets one companion to a {@link PathGoal}: searches with a per-tick time budget, executes the route, re-plans when a
 * step fails, and executes partial routes when a search reaches its limits.
 *
 * <p>A new goal does not stop the companion: it keeps walking the old route while the new search runs, and switches
 * when the search returns a route. Nothing here touches the world off the server thread.
 */
public final class Navigator {
    public enum State { SEARCHING, MOVING, GATHERING, ARRIVED, UNREACHABLE }

    /** Re-plans without getting closer than this many ticks of cost are given up after three tries. */
    private static final int REPLAN_LIMIT = 3;
    /** How many times one goal may send the companion out to gather before giving up. */
    private static final int GATHER_ROUNDS = 2;
    private static final double PROGRESS = 1.0;
    /** How many leading steps of a new route may be skipped when the companion has already walked past them. */
    private static final int TRIM_LOOKAHEAD = 8;

    private final CompanionEntity companion;
    /** Whether a route may place blocks. Approaching a block to act on it walks and digs, but never builds. */
    private final boolean mayPlace;
    /** Settings for this route, or null to read the live server settings on every use. */
    private final @Nullable PathingConfig override;
    private PathGoal goal;
    private PathSearch search;
    private Path plan;
    private PathExecutor executor;
    private State state = State.SEARCHING;
    private long searchNanos;
    private int failedPlans;
    private double bestHeuristic = Double.POSITIVE_INFINITY;
    /** Whether the running search is the material probe (2.5), which ignores the inventory. */
    private boolean probing;
    /** How many blocks the inventory held when the probe started; the probe path may want more. */
    private int availableAtProbe;
    private MaterialGatherer gatherer;
    private int gatherRounds;
    /** The last search's summary, for the movement debug overlay. */
    private String lastSearch = "";

    public Navigator(CompanionEntity companion) {
        this(companion, true, null);
    }

    /**
     * @param mayPlace whether the route may pillar or bridge. An approach to a block that is only there to be used
     *     (a chest, a block a task places or breaks) keeps it false: the task owns the terrain and the inventory it
     *     brought for its own work, and a pillar of scaffold blocks would spend both.
     */
    public Navigator(CompanionEntity companion, boolean mayPlace) {
        this(companion, mayPlace, null);
    }

    /**
     * @param override settings for this route only, or null to read the live server settings. A collector that must
     *     not change the terrain walks with digging and placing switched off.
     */
    public Navigator(CompanionEntity companion, boolean mayPlace, @Nullable PathingConfig override) {
        this.companion = companion;
        this.mayPlace = mayPlace;
        this.override = override;
    }

    /** The settings this route uses: its own, or the live server settings. */
    private PathingConfig config() {
        return override != null ? override : TaskSettings.pathing;
    }

    public State state() {
        return state;
    }

    /** The route being walked, or null while none is. */
    public @Nullable Path plan() {
        return executor == null ? null : plan;
    }

    /** How many steps of {@link #plan()} are finished; -1 while no route is walked. */
    public int stepIndex() {
        return executor == null ? -1 : executor.stepIndex();
    }

    public int failedPlans() {
        return failedPlans;
    }

    public boolean searching() {
        return search != null;
    }

    public String lastSearch() {
        return lastSearch;
    }

    /** The cells the goal asks for, for the movement debug overlay; a point goal gives the cell holding the point. */
    public List<Pos> goalCells() {
        if (goal instanceof GoalPoint point) {
            return List.of(new Pos((int) Math.floor(point.x()), (int) Math.floor(point.y()), (int) Math.floor(point.z())));
        }
        if (goal instanceof GoalReach reach) {
            return reach.targets().subList(0, Math.min(reach.targets().size(), 64));
        }
        return List.of();
    }

    /** A short description of the goal for the movement debug overlay. */
    public String goalText() {
        return goal == null ? "none" : describe(goal);
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
        bestHeuristic = goal.heuristic(node(), CostModel.DEFAULTS);
        probing = false;
        gatherRounds = 0;
        startSearch();
        if (executor == null) {
            state = State.SEARCHING;
        }
    }

    public State tick(TaskContext c) {
        companion.noteNavigator(this);
        if (state == State.ARRIVED || state == State.UNREACHABLE) {
            return state;
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
        companion.setPathing(false);
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
        PathingConfig settings = config();
        LevelCellView view = new LevelCellView(companion, settings, CostModel.DEFAULTS);
        availableAtProbe = new MaterialPicker(settings).count(companion.inventory());
        probing = true;
        search = new PathSearch(view, CostModel.DEFAULTS, goal, node(),
                new SearchOptions(settings.maxNodes(), 64, Integer.MAX_VALUE, 1));
        state = State.SEARCHING;
    }

    private void startGathering(TaskContext c, Path path) {
        if (path == null || path.steps().isEmpty() || path.places() <= availableAtProbe) {
            state = State.UNREACHABLE;
            return;
        }
        int missing = path.places() - availableAtProbe;
        // A few spare blocks: an extra bridge cell or a pillar is cheaper to gather now than to walk back for.
        int needed = Math.min(missing + 4, config().maxGatherBlocks());
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
        long budget = config().searchBudgetMs() * 1_000_000L;
        long begin = System.nanoTime();
        SearchResult result = search.step(budget, System::nanoTime);
        searchNanos += System.nanoTime() - begin;
        if (result.status() != SearchResult.Status.RUNNING) {
            handleSearchResult(c, result);
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
        lastSearch = line.toString();
        search = null;
        if (result.status() == SearchResult.Status.FOUND && path != null && path.steps().isEmpty()) {
            // The start node already is the goal: there is nothing to walk.
            state = State.ARRIVED;
            return;
        }
        if (probed) {
            if (result.status() != SearchResult.Status.PARTIAL) {
                startGathering(c, path);
                return;
            }
            // A range-limited probe may contain more construction than the inventory can afford.  Walk its
            // affordable prefix first: it gets the companion closer without committing to gather an unknown
            // amount.  If construction is the very first move, gather only enough for that first step.
            Path prefix = affordablePrefix(path, availableAtProbe);
            if (prefix != null) {
                beginExecution(c, prefix);
                return;
            }
            startGathering(c, firstUnaffordableStep(path, availableAtProbe));
            return;
        }
        if (result.status() == SearchResult.Status.NO_PATH || path == null || path.steps().isEmpty()) {
            if (!mayPlace || !config().allowPlace() || gatherRounds >= GATHER_ROUNDS) {
                state = State.UNREACHABLE;
                return;
            }
            // A missing route may mean missing material: ask what it would take with unlimited blocks to place.
            beginProbe();
            return;
        }
        beginExecution(c, path);
    }

    private void beginExecution(TaskContext c, Path path) {
        if (executor != null) {
            executor.stop(c);
        }
        plan = path.afterNode(node(), TRIM_LOOKAHEAD);
        executor = new PathExecutor(plan, new MaterialPicker(config()), config());
        state = State.MOVING;
    }

    /** Returns the longest prefix whose placements the probe-start inventory can pay for. */
    private static @Nullable Path affordablePrefix(Path path, int available) {
        int places = 0;
        int breaks = 0;
        double cost = 0.0;
        List<PathStep> steps = new ArrayList<>();
        for (PathStep step : path.steps()) {
            int nextPlaces = places + step.places().size();
            if (nextPlaces > available) {
                break;
            }
            steps.add(step);
            places = nextPlaces;
            breaks += step.breaks().size();
            cost += step.cost();
        }
        return steps.isEmpty() ? null : new Path(steps, cost, breaks, places, true);
    }

    /** Retains just enough of a probe path to determine the first unavailable construction cost. */
    private static @Nullable Path firstUnaffordableStep(Path path, int available) {
        int places = 0;
        int breaks = 0;
        double cost = 0.0;
        List<PathStep> steps = new ArrayList<>();
        for (PathStep step : path.steps()) {
            steps.add(step);
            places += step.places().size();
            breaks += step.breaks().size();
            cost += step.cost();
            if (places > available) {
                return new Path(steps, cost, breaks, places, true);
            }
        }
        return null;
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
            // A partial route that did not reduce the admissible distance would otherwise be planned and replayed
            // forever around an unreachable target.
            notePlanFailure();
            if (failedPlans >= REPLAN_LIMIT) {
                state = State.UNREACHABLE;
                return state;
            }
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
        double heuristic = goal.heuristic(node(), CostModel.DEFAULTS);
        if (heuristic < bestHeuristic - PROGRESS) {
            bestHeuristic = heuristic;
            failedPlans = 1;
        } else {
            failedPlans++;
        }
    }

    private void startSearch() {
        PathingConfig settings = config();
        MaterialPicker materials = new MaterialPicker(settings);
        int placeable = mayPlace ? materials.count(companion.inventory()) : 0;
        double price = mayPlace ? materials.cheapestPrice(companion.inventory()) : 0.0;
        LevelCellView view = new LevelCellView(companion, settings, CostModel.DEFAULTS);
        SearchOptions options = new SearchOptions(settings.maxNodes(), 64, placeable, price);
        search = new PathSearch(view, CostModel.DEFAULTS, goal, node(), options);
    }

    private boolean goalHolds() {
        return goal.isGoal(node(), new LevelCellView(companion, config(), CostModel.DEFAULTS));
    }

    private Node node() {
        var pos = companion.blockPosition();
        LevelCellView view = new LevelCellView(companion, config(), CostModel.DEFAULTS);
        // A partial support physically contains the feet, while the pure grid puts its node above the support.
        int y = pos.getY();
        if (companion.onGround() && view.at(pos.getX(), y, pos.getZ()).kind() == dev.friendai.pathing.Cell.Kind.PARTIAL) {
            y++;
        }
        return new Node(pos.getX(), y, pos.getZ());
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
