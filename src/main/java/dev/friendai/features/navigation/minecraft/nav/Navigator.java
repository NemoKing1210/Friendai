package dev.friendai.features.navigation.minecraft.nav;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.features.navigation.pathing.BlockList;
import dev.friendai.features.navigation.pathing.CostModel;
import dev.friendai.features.navigation.pathing.GoalPoint;
import dev.friendai.features.navigation.pathing.Node;
import dev.friendai.features.navigation.pathing.Path;
import dev.friendai.features.navigation.pathing.PathGoal;
import dev.friendai.features.navigation.pathing.PathSearch;
import dev.friendai.features.navigation.pathing.PathSmoother;
import dev.friendai.features.navigation.pathing.PathStep;
import dev.friendai.features.navigation.pathing.PathingConfig;
import dev.friendai.features.navigation.pathing.Pos;
import dev.friendai.features.navigation.pathing.SearchOptions;
import dev.friendai.features.navigation.pathing.SearchResult;
import dev.friendai.task.TaskContext;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

/**
 * Gets one companion to a {@link PathGoal}: searches with a per-tick time budget, executes the route, re-plans when a
 * step fails, and executes partial routes when a search reaches its limits.
 *
 * <p>A new goal does not stop the companion: it keeps walking the old route while the new search runs, and switches
 * when the search returns a route. Nothing here touches the world off the server thread.
 */
public final class Navigator {
    public enum State {
        SEARCHING,
        MOVING,
        GATHERING,
        ARRIVED,
        UNREACHABLE
    }

    /** Re-plans without getting closer than this many ticks of cost are given up after three tries. */
    private static final int REPLAN_LIMIT = 3;
    /** How many times one goal may send the companion out to gather before giving up. */
    private static final int GATHER_ROUNDS = 2;

    private static final double PROGRESS = 1.0;
    /** How many leading steps of a new route may be skipped when the companion has already walked past them. */
    private static final int TRIM_LOOKAHEAD = 8;

    private final CompanionEntity companion;
    private final PathingConfig settings;
    private final BlockList breakList;
    private final BlockList placeList;
    /** Whether a route may place blocks. Approaching a block to act on it walks and digs, but never builds. */
    private final boolean mayPlace;
    /** Route-local action overrides; when absent, the route uses its captured server settings snapshot. */
    private final @Nullable PathingConfig override;
    /** Where blocks this route places are remembered for cleanup, or null when they are meant to stay. */
    private final @Nullable PlacementLedger ledger;

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
    /** The context of the last tick, so a goal change can stop the gatherer with the task's own settings. */
    private @Nullable TaskContext lastContext;
    /** A stopped navigator stays parked until a new goal arrives; it never restarts a search on its own. */
    private boolean stopped;

    public Navigator(TaskContext context) {
        this(context, true, null, null);
    }

    /**
     * @param mayPlace whether the route may pillar or bridge. An approach to a block that is only there to be used
     *     (a chest, a block a task places or breaks) keeps it false: the task owns the terrain and the inventory it
     *     brought for its own work, and a pillar of scaffold blocks would spend both.
     */
    public Navigator(TaskContext context, boolean mayPlace) {
        this(context, mayPlace, null, null);
    }

    /**
     * @param override settings for this route only, or null to use the task's captured snapshot. A collector that
     *     must not change the terrain walks with digging and placing switched off.
     */
    public Navigator(TaskContext context, boolean mayPlace, @Nullable PathingConfig override) {
        this(context, mayPlace, override, null);
    }

    /**
     * @param ledger records every block this route places so the caller can remove a temporary pillar or bridge;
     *     null for routes whose placements are meant to stay.
     */
    public Navigator(
            TaskContext context, boolean mayPlace, @Nullable PathingConfig override, @Nullable PlacementLedger ledger) {
        this.companion = context.companion();
        this.settings = context.pathingConfig();
        boolean usesServerLists = override == null
                || override.breakBlacklist().equals(settings.breakBlacklist())
                        && override.placeBlacklist().equals(settings.placeBlacklist());
        if (usesServerLists) {
            this.breakList = context.breakList();
            this.placeList = context.placeList();
        } else {
            this.breakList = BlockList.parse(override.breakBlacklist(), ignored -> {});
            java.util.ArrayList<String> entries = new java.util.ArrayList<>(override.breakBlacklist());
            entries.addAll(override.placeBlacklist());
            this.placeList = BlockList.parse(entries, ignored -> {});
        }
        this.mayPlace = mayPlace;
        this.override = override;
        this.ledger = ledger;
    }

    /** The settings this route captured when it was created, optionally adjusted for route-local actions. */
    private PathingConfig config() {
        return override != null ? override : settings;
    }

    private MaterialPicker materials(PathingConfig config) {
        return new MaterialPicker(config, breakList, placeList);
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

    /** Horizontal blocks left on the route being walked, or 0 while none is. */
    public double remainingDistance() {
        return executor == null ? 0.0 : executor.remainingDistance(companion);
    }

    public boolean searching() {
        return search != null;
    }

    public String lastSearch() {
        return lastSearch;
    }

    /** The cells the goal asks for, for the movement debug overlay. The goal bounds its own list. */
    public List<Pos> goalCells() {
        return goal == null ? List.of() : goal.cells();
    }

    /** A short description of the goal for the movement debug overlay. */
    public String goalText() {
        return goal == null ? "none" : goal.label();
    }

    public PathGoal goal() {
        return goal;
    }

    /**
     * Aims at a new goal. While the new search runs the companion keeps walking the route it already had; the route
     * is replaced when a search finds a way to the new goal.
     */
    public void setGoal(PathGoal goal) {
        if (goal == null
                || !stopped
                        && goal.equals(this.goal)
                        && (search != null
                                || executor != null
                                || state == State.SEARCHING
                                || state == State.GATHERING)) {
            return;
        }
        if (gatherer != null) {
            // A gatherer that was never ticked owns nothing to stop; one that was knows its task's settings.
            if (lastContext != null) {
                gatherer.stop(lastContext);
            }
            gatherer = null;
        }
        stopped = false;
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
        lastContext = c;
        companion.noteNavigator(this);
        if (stopped || state == State.ARRIVED || state == State.UNREACHABLE) {
            return state;
        }
        if (search != null) {
            stepSearch(c);
            // The result of this tick's search is newer than the route it replaced: a stale executor tick must not
            // report MOVING over an ARRIVED or UNREACHABLE answer.
            if (state == State.ARRIVED || state == State.UNREACHABLE) {
                return state;
            }
        }
        if (executor != null) {
            return tickExecutor(c);
        }
        if (state == State.GATHERING) {
            tickGathering(c);
            return state;
        }
        state = State.SEARCHING;
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
        stopped = true;
        state = State.SEARCHING;
        companion.getNavigation().stop();
        companion.setPathing(false);
    }

    /**
     * Ends the route being walked. A terminal search result or a gathering trip owns the body from then on; leaving
     * the old executor alive would let its next tick report MOVING over that decision.
     */
    private void abandonExecutor(TaskContext c) {
        if (executor != null) {
            executor.stop(c);
            executor = null;
        }
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
        LevelCellView view = new LevelCellView(companion, settings, CostModel.DEFAULTS, breakList);
        availableAtProbe = materials(settings).count(companion.inventory());
        probing = true;
        searchNanos = 0;
        search = new PathSearch(
                view,
                CostModel.DEFAULTS,
                goal,
                node(),
                new SearchOptions(
                        settings.maxNodes(), PathingConfig.SEARCH_RANGE, Integer.MAX_VALUE, 1, companion.canSprint()));
        state = State.SEARCHING;
    }

    private void startGathering(TaskContext c, @Nullable Path path) {
        // Gathering owns the body: the route it was replacing must not keep digging or building toward the old goal.
        abandonExecutor(c);
        if (path == null || path.steps().isEmpty() || path.places() <= availableAtProbe) {
            state = State.UNREACHABLE;
            return;
        }
        int missing = path.places() - availableAtProbe;
        // A few spare blocks: an extra bridge cell or a pillar is cheaper to gather now than to walk back for.
        PathingConfig route = config();
        int needed = Math.min(missing + 4, route.maxGatherBlocks());
        gatherer = new MaterialGatherer(needed, avoidCells(path), route, breakList, placeList);
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
        if (executor != null) {
            // The old route is walked in this tick as well, so the opportunistic re-plan takes half the budget: the
            // whole tick then costs one search budget instead of two full phases.
            budget = Math.max(1L, budget / 2);
        }
        long begin = System.nanoTime();
        SearchResult result = search.step(budget, System::nanoTime);
        searchNanos += System.nanoTime() - begin;
        if (result.status() != SearchResult.Status.RUNNING) {
            handleSearchResult(c, result);
        }
    }

    private void handleSearchResult(TaskContext c, SearchResult result) {
        Path path = result.path();
        StringBuilder line =
                new StringBuilder("path ").append(goal().label()).append(": ").append(result.status());
        line.append(", ")
                .append(search.expanded())
                .append(" nodes, ")
                .append(searchNanos / 1_000_000L)
                .append(" ms");
        if (path != null) {
            line.append(", cost ")
                    .append(Math.round(path.cost()))
                    .append(", breaks ")
                    .append(path.breaks())
                    .append(", places ")
                    .append(path.places())
                    .append(path.partial() ? ", partial" : "");
        }
        boolean probed = probing;
        probing = false;
        if (probed) {
            line.append(", material probe");
        }
        c.debug(line.toString());
        lastSearch = line.toString();
        search = null;
        if (result.status() == SearchResult.Status.FOUND
                && path != null
                && path.steps().isEmpty()) {
            // The start node already is the goal: there is nothing to walk.
            abandonExecutor(c);
            state = State.ARRIVED;
            return;
        }
        if (probed) {
            if (path == null || path.steps().isEmpty()) {
                // A probe that finds no route at all cannot be a missing material: the companion is walled in.
                abandonExecutor(c);
                state = State.UNREACHABLE;
                return;
            }
            if (result.status() == SearchResult.Status.FOUND) {
                startGathering(c, path);
                return;
            }
            // A probe that could not finish is limited by range or the node budget, not by material. Walk what it
            // planned before its first placement; a half-built stair is never walked.
            Path prefix = path.passagePrefix().orElse(null);
            if (prefix != null) {
                beginExecution(c, prefix);
                return;
            }
            startGathering(c, firstUnaffordableStep(path, availableAtProbe));
            return;
        }
        if (result.status() == SearchResult.Status.NO_PATH
                || path == null
                || path.steps().isEmpty()) {
            if (!mayPlace || !config().allowPlace() || gatherRounds >= GATHER_ROUNDS) {
                state = State.UNREACHABLE;
                return;
            }
            // A missing route may mean missing material: ask what it would take with unlimited blocks to place.
            beginProbe();
            return;
        }
        Path passage = path.passagePrefix().orElse(null);
        if (passage == null) {
            // The search stopped at the first rise of a stair or bridge it cannot finish with what the companion
            // carries: walking it would spend the block and strand the companion. Learn the price of the whole
            // passage (or the rest of it) and gather that instead.
            if (!mayPlace || !config().allowPlace() || gatherRounds >= GATHER_ROUNDS) {
                state = State.UNREACHABLE;
                return;
            }
            beginProbe();
            return;
        }
        beginExecution(c, passage);
    }

    private void beginExecution(TaskContext c, Path path) {
        if (executor != null) {
            executor.stop(c);
        }
        Path trimmed = path.afterPosition(node().pos(), TRIM_LOOKAHEAD);
        plan = PathSmoother.simplify(trimmed, new LevelCellView(companion, config(), CostModel.DEFAULTS, breakList));
        executor = new PathExecutor(plan, materials(config()), config(), PathExecutionGuard.DEFAULT, ledger, breakList);
        state = State.MOVING;
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
        MaterialPicker materials = materials(settings);
        int placeable = mayPlace ? materials.count(companion.inventory()) : 0;
        double price = mayPlace ? materials.cheapestPrice(companion.inventory()) : 0.0;
        LevelCellView view = new LevelCellView(companion, settings, CostModel.DEFAULTS, breakList);
        SearchOptions options = new SearchOptions(
                settings.maxNodes(), PathingConfig.SEARCH_RANGE, placeable, price, companion.canSprint());
        searchNanos = 0;
        search = new PathSearch(view, CostModel.DEFAULTS, goal, node(), options);
    }

    private boolean goalHolds() {
        if (goal instanceof GoalPoint point) {
            // The search judges a block node; at run time the body may already be inside the goal block while its
            // feet are a fraction below the node (the top of a climb). Compare the body, not the node floor.
            double dx = companion.getX() - point.x();
            double dz = companion.getZ() - point.z();
            if (Math.sqrt(dx * dx + dz * dz) <= point.horizontal()
                    && Math.abs(companion.getY() - point.y()) <= point.vertical()) {
                return true;
            }
        }
        return goal.isGoal(node(), new LevelCellView(companion, config(), CostModel.DEFAULTS, breakList));
    }

    private Node node() {
        var pos = companion.blockPosition();
        LevelCellView view = new LevelCellView(companion, config(), CostModel.DEFAULTS, breakList);
        // A partial support physically contains the feet, while the pure grid puts its node above the support.
        int y = pos.getY();
        if (companion.onGround()
                && view.at(pos.getX(), y, pos.getZ()).kind()
                        == dev.friendai.features.navigation.pathing.Cell.Kind.PARTIAL) {
            y++;
        }
        return new Node(pos.getX(), y, pos.getZ());
    }
}
