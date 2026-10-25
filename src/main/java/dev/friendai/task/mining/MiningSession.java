package dev.friendai.task.mining;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.mining.ExplorationPlanner;
import dev.friendai.mining.MiningBounds;
import dev.friendai.mining.MiningGoal;
import dev.friendai.mining.MiningPathPolicy;
import dev.friendai.pathing.CostModel;
import dev.friendai.pathing.Move;
import dev.friendai.pathing.Node;
import dev.friendai.pathing.Path;
import dev.friendai.pathing.PathPolicy;
import dev.friendai.pathing.PathSearch;
import dev.friendai.pathing.PathStep;
import dev.friendai.pathing.PathingConfig;
import dev.friendai.pathing.Pos;
import dev.friendai.pathing.SearchOptions;
import dev.friendai.pathing.SearchResult;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskSettings;
import dev.friendai.task.nav.Approach;
import dev.friendai.task.nav.MaterialPicker;
import dev.friendai.task.nav.PathExecutor;
import dev.friendai.text.UserMessage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/** Shared bounded search and guarded execution for resource extraction and underground exploration. */
public final class MiningSession {

    public enum State { WORKING, EXTRACTED, EXHAUSTED, FAILED }
    private enum Mode { TARGETS, EXPLORE }

    private final CompanionEntity companion;
    private final MiningBounds bounds;
    private final int maxBlocks;
    private final int maxTicks;
    private final Node initialNode;
    private final Set<Pos> supports = new HashSet<>();
    private final Set<Pos> excludedTargets = new HashSet<>();
    private List<BlockPos> targets = List.of();
    private Predicate<BlockState> matches = state -> false;
    private Mode mode;
    private int targetY;
    private int heading;
    private int ticks;
    private int brokenBlocks;
    private int completedExplorationSteps;
    private int noProgressPlans;
    private boolean mustRelocateFromSupport;
    private PathSearch search;
    private MiningGoal goal;
    private ExplorationPlanner exploration;
    private PathExecutor executor;
    private BreakAction extraction;
    private BlockPos target;
    private State lastState = State.WORKING;
    private UserMessage failure;
    private String phase = "approach";

    public MiningSession(CompanionEntity companion, MiningBounds bounds, int maxBlocks, int maxTicks) {
        if (maxBlocks < 0 || maxTicks < 1) {
            throw new IllegalArgumentException("Mining limits must be non-negative blocks and positive ticks");
        }
        this.companion = companion;
        this.bounds = bounds;
        this.maxBlocks = maxBlocks;
        this.maxTicks = maxTicks;
        BlockPos initial = companion.blockPosition();
        this.initialNode = new Node(initial.getX(), initial.getY(), initial.getZ());
    }

    /** Selects a batch of explicitly requested resources while keeping this session's route and limits. */
    public void setTargets(List<BlockPos> targets, Predicate<BlockState> matches) {
        if (executor != null || extraction != null) {
            throw new IllegalStateException("Cannot replace mining targets while an action is active");
        }
        this.mode = Mode.TARGETS;
        this.targets = List.copyOf(targets);
        this.matches = matches;
        this.target = null;
        this.failure = null;
        this.lastState = State.WORKING;
        this.search = null;
        this.goal = null;
        this.exploration = null;
        this.excludedTargets.clear();
        this.mustRelocateFromSupport = this.targets.stream().anyMatch(pos -> pos.equals(blockPos(initialNode.support())));
        rememberCurrentSupport();
    }

    /** Selects one underground exploration round; heading uses north, east, south, west as 0..3. */
    public void explore(int targetY, int heading) {
        if (executor != null || extraction != null) {
            throw new IllegalStateException("Cannot replace exploration while an action is active");
        }
        this.mode = Mode.EXPLORE;
        this.targetY = targetY;
        this.heading = heading;
        this.targets = List.of();
        this.target = null;
        this.failure = null;
        this.lastState = State.WORKING;
        this.search = null;
        this.goal = null;
        this.exploration = null;
        rememberCurrentSupport();
    }

    public State tick(TaskContext context) {
        if (lastState == State.FAILED || lastState == State.EXHAUSTED) {
            return lastState;
        }
        if (++ticks > maxTicks) {
            lastState = State.EXHAUSTED;
            failure = UserMessage.of("friendai.mining.search_limit");
            stop(context);
            return lastState;
        }
        if (executor != null) {
            return tickExecutor(context);
        }
        if (extraction != null) {
            return tickExtraction(context);
        }
        if (mode == Mode.EXPLORE) {
            return tickExplore(context);
        }
        return tickTargets(context);
    }

    public BlockPos target() {
        return target;
    }

    public UserMessage failure() {
        return failure;
    }

    public String phase() {
        return phase;
    }

    public int brokenBlocks() {
        return brokenBlocks;
    }

    public int completedExplorationSteps() {
        return completedExplorationSteps;
    }

    public void stop(TaskContext context) {
        if (executor != null) {
            executor.stop(context);
            executor = null;
        }
        if (extraction != null) {
            extraction.stop(context);
            extraction = null;
        }
        search = null;
        exploration = null;
    }

    private State tickTargets(TaskContext context) {
        if (targets.isEmpty()) {
            return fail("friendai.mining.unreachable");
        }
        if (search == null) {
            createTargetSearch(context);
        }
        SearchResult result = search.step(searchBudget(), System::nanoTime);
        if (result.status() == SearchResult.Status.RUNNING) {
            phase = "approach";
            return State.WORKING;
        }
        Path path = result.path();
        if (result.status() == SearchResult.Status.NO_PATH || path == null) {
            return retryPlan("friendai.mining.unreachable");
        }
        Node current = node();
        if (path.steps().isEmpty()) {
            var visibleTarget = goal.targetAt(current, guard(context).planningView(context, TaskSettings.pathing));
            if (visibleTarget.isEmpty()) {
                return retryPlan("friendai.mining.unreachable");
            }
            target = blockPos(visibleTarget.get());
            phase = "extract";
            search = null;
            if (!guard(context).allowTarget(context, target)) {
                excludedTargets.add(plain(target));
                return retryPlan("friendai.mining.unsafe_support");
            }
            extraction = new BreakAction(target);
            return State.WORKING;
        }
        return executeEdge(context, path.steps().getFirst());
    }

    private State tickExplore(TaskContext context) {
        if (exploration == null) {
            var view = guard(context).planningView(context, TaskSettings.pathing);
            exploration = new ExplorationPlanner(view, node(), targetY, heading, bounds, supports,
                    CostModel.DEFAULTS, TaskSettings.pathing.maxNodes());
        }
        ExplorationPlanner.Status result = exploration.step(searchBudget(), System::nanoTime);
        if (result == ExplorationPlanner.Status.RUNNING) {
            phase = "descend";
            return State.WORKING;
        }
        if (result == ExplorationPlanner.Status.NO_ROUTE) {
            exploration = null;
            return retryPlan("friendai.mining.unreachable");
        }
        PathStep next = exploration.nextStep().orElseThrow();
        phase = phaseFor(next.move());
        exploration = null;
        return executeEdge(context, next);
    }

    private State tickExecutor(TaskContext context) {
        PathExecutor.State state = executor.tick(context);
        if (state == PathExecutor.State.RUNNING) {
            return State.WORKING;
        }
        if (state == PathExecutor.State.FAILED) {
            executor.stop(context);
            executor = null;
            search = null;
            exploration = null;
            return retryPlan("friendai.mining.unsafe_passage");
        }
        PathStep completed = executorPathStep();
        brokenBlocks += executor.brokenBlocks();
        executor.stop(context);
        executor = null;
        rememberSupport(completed.from());
        rememberSupport(completed.to());
        if (mode == Mode.EXPLORE && completed.to().y() <= targetY
                && Math.abs(completed.from().x() - completed.to().x())
                        + Math.abs(completed.from().z() - completed.to().z()) == 1) {
            completedExplorationSteps++;
        }
        noProgressPlans = 0;
        activeStep = null;
        if (mustRelocateFromSupport && !node().equals(initialNode)) {
            mustRelocateFromSupport = false;
        }
        search = null;
        exploration = null;
        return State.WORKING;
    }

    private State tickExtraction(TaskContext context) {
        if (target == null || !guard(context).allowTarget(context, target)) {
            if (extraction != null) {
                extraction.stop(context);
                extraction = null;
            }
            if (target != null) {
                excludedTargets.add(plain(target));
            }
            return retryPlan("friendai.mining.unsafe_support");
        }
        BreakAction.State result = extraction.tick(context, matches);
        if (result == BreakAction.State.WORKING) {
            phase = "extract";
            return State.WORKING;
        }
        extraction.stop(context);
        extraction = null;
        if (result == BreakAction.State.BROKEN) {
            noProgressPlans = 0;
            search = null;
            return lastState = State.EXTRACTED;
        }
        if (target != null) {
            excludedTargets.add(plain(target));
        }
        target = null;
        search = null;
        return retryPlan(result == BreakAction.State.BLOCKED
                ? "friendai.mining.unsafe_support" : "friendai.mining.unreachable");
    }

    private State executeEdge(TaskContext context, PathStep step) {
        long remaining = maxBlocks - brokenBlocks;
        if (step.breaks().size() > remaining) {
            return lastState = State.EXHAUSTED;
        }
        Path oneEdge = new Path(List.of(step), step.cost(), step.breaks().size(), step.places().size(), false);
        PathingConfig config = TaskSettings.pathing.withActions(true, false);
        executor = new PathExecutor(oneEdge, new MaterialPicker(config), config, guard(context));
        activeStep = step;
        search = null;
        return State.WORKING;
    }

    private void createTargetSearch(TaskContext context) {
        var view = guard(context).planningView(context, TaskSettings.pathing);
        List<Pos> candidates = new ArrayList<>();
        for (BlockPos candidate : targets) {
            Pos plain = plain(candidate);
            if (!excludedTargets.contains(plain)) {
                candidates.add(plain);
            }
        }
        goal = new MiningGoal(candidates, Approach.REACH, companion.getEyeHeight(), supports);
        MiningPathPolicy miningPolicy = new MiningPathPolicy(view, bounds, supports, CostModel.DEFAULTS);
        PathPolicy policy = (freshView, step) -> {
            if (mustRelocateFromSupport && step.from().equals(initialNode) && !step.breaks().isEmpty()) {
                return java.util.Optional.empty();
            }
            return miningPolicy.prepare(freshView, step);
        };
        search = new PathSearch(view, CostModel.DEFAULTS, goal, node(),
                new SearchOptions(TaskSettings.pathing.maxNodes(), bounds.radius(), 0, 1), policy);
    }

    private void rememberCurrentSupport() {
        Pos support = node().support();
        if (!targets.contains(blockPos(support))) {
            supports.add(support);
        }
    }

    private void rememberSupport(Node at) {
        Pos support = at.support();
        if (!targets.contains(blockPos(support))) {
            supports.add(support);
        }
    }

    private State retryPlan(String key) {
        noProgressPlans++;
        search = null;
        exploration = null;
        if (noProgressPlans >= 3) {
            return fail(key);
        }
        return State.WORKING;
    }

    private State fail(String key) {
        failure = UserMessage.of(key);
        lastState = State.FAILED;
        return lastState;
    }

    private long searchBudget() {
        return Math.max(0, TaskSettings.pathing.searchBudgetMs()) * 1_000_000L;
    }

    private ExcavationGuard guard(TaskContext context) {
        return new ExcavationGuard(bounds, supports);
    }

    private Node node() {
        BlockPos pos = companion.blockPosition();
        return new Node(pos.getX(), pos.getY(), pos.getZ());
    }

    private PathStep executorPathStep() {
        // Every executor owned by a session contains exactly one edge.
        return activeStep;
    }

    private PathStep activeStep;

    private static String phaseFor(Move move) {
        return switch (move) {
            case ASCEND, DIG_ASCEND -> "ascend";
            case DESCEND, DIG_DESCEND -> "descend";
            case DIG -> "tunnel";
            default -> "approach";
        };
    }

    private static Pos plain(BlockPos pos) {
        return new Pos(pos.getX(), pos.getY(), pos.getZ());
    }

    private static BlockPos blockPos(Pos pos) {
        return new BlockPos(pos.x(), pos.y(), pos.z());
    }

    private static BlockPos blockPos(Pos pos, boolean ignored) {
        return blockPos(pos);
    }

    private static Pos plain(BlockPos pos, boolean ignored) {
        return plain(pos);
    }
}
