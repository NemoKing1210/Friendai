package dev.friendai.task.mining;

import dev.friendai.core.Components;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.features.mining.ExplorationPlanner;
import dev.friendai.features.mining.MiningBounds;
import dev.friendai.features.mining.MiningGoal;
import dev.friendai.features.mining.MiningPathPolicy;
import dev.friendai.features.navigation.minecraft.nav.Approach;
import dev.friendai.features.navigation.minecraft.nav.MaterialPicker;
import dev.friendai.features.navigation.minecraft.nav.PathExecutor;
import dev.friendai.features.navigation.pathing.CostModel;
import dev.friendai.features.navigation.pathing.Move;
import dev.friendai.features.navigation.pathing.Node;
import dev.friendai.features.navigation.pathing.Path;
import dev.friendai.features.navigation.pathing.PathPolicy;
import dev.friendai.features.navigation.pathing.PathSearch;
import dev.friendai.features.navigation.pathing.PathStep;
import dev.friendai.features.navigation.pathing.PathingConfig;
import dev.friendai.features.navigation.pathing.Pos;
import dev.friendai.features.navigation.pathing.SearchOptions;
import dev.friendai.features.navigation.pathing.SearchResult;
import dev.friendai.foundation.text.UserMessage;
import dev.friendai.task.TaskContext;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/** Shared bounded search and guarded execution for resource extraction and underground exploration. */
public final class MiningSession {

    public enum State {
        WORKING,
        EXTRACTED,
        EXHAUSTED,
        FAILED
    }

    private enum Mode {
        TARGETS,
        EXPLORE
    }

    /** How many planned steps one search may commit to; a search per block would leave the companion standing still. */
    private static final int MAX_CHAIN = 8;

    private final CompanionEntity companion;
    private final MiningBounds bounds;
    private final int maxBlocks;
    private final int maxTicks;
    private final Node initialNode;
    private final Set<Pos> supports = new HashSet<>();
    private final Set<Pos> excludedTargets = new HashSet<>();
    private final Set<Node> excludedStances = new HashSet<>();
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
    private boolean closeApproach;
    private PathSearch search;
    private MiningGoal goal;
    private ExplorationPlanner exploration;
    private PathExecutor executor;
    private BreakAction extraction;
    private BlockPos target;
    private State lastState = State.WORKING;
    private UserMessage failure;
    /** The first concrete refusal this session hit, kept when later attempts have nothing left to try. */
    private UserMessage primaryFailure;
    /** A refusal caused by the wrong tool; it must not be replaced by the generic "unreachable" that follows. */
    private UserMessage toolFailure;

    private String phase = "";

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
        this.primaryFailure = null;
        this.toolFailure = null;
        this.lastState = State.WORKING;
        this.search = null;
        this.goal = null;
        this.exploration = null;
        this.excludedTargets.clear();
        this.excludedStances.clear();
        this.mustRelocateFromSupport =
                this.targets.stream().anyMatch(pos -> pos.equals(blockPos(initialNode.support())));
        this.closeApproach = false;
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
        this.primaryFailure = null;
        this.toolFailure = null;
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
            return exhaust(context);
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

    /** Immutable copy of the current task-owned candidate set for diagnostics. */
    public List<BlockPos> targets() {
        return targets.stream().map(BlockPos::immutable).toList();
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

    /** Previously used supports stay intact so the companion can return along its route. */
    public boolean protects(BlockPos pos) {
        return supports.contains(plain(pos));
    }

    public int protectedSupportCount() {
        return supports.size();
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
        SearchResult result = search.step(searchBudget(context), System::nanoTime);
        if (result.status() == SearchResult.Status.RUNNING) {
            setPhase("approach");
            return State.WORKING;
        }
        Path path = result.path();
        if (result.status() == SearchResult.Status.NO_PATH || path == null) {
            if (!closeApproach) {
                closeApproach = true;
                search = null;
                return State.WORKING;
            }
            return retryPlan("friendai.mining.unreachable");
        }
        Node current = node();
        if (path.steps().isEmpty()) {
            var visibleTarget = goal.targetAt(
                    current,
                    guard(context)
                            .planningView(context, context.serverSettings().pathing()));
            if (visibleTarget.isEmpty()) {
                excludedStances.add(current);
                return retryPlan("friendai.mining.unreachable");
            }
            target = blockPos(visibleTarget.get());
            setPhase("extract");
            search = null;
            if (!guard(context).allowTarget(context, target)) {
                UserMessage reason = guard(context).targetFailure(context, target);
                if (reason.key().equals("friendai.mining.no_tool")
                        || reason.key().equals("friendai.mining.unsafe_passage")
                        || !companion.hands().canInteract(target)) {
                    excludedTargets.add(plain(target));
                } else {
                    excludedStances.add(current);
                }
                return retryPlan(reason);
            }
            extraction = new BreakAction(target);
            return State.WORKING;
        }
        return executeSteps(
                context, path.steps().subList(0, Math.min(path.steps().size(), MAX_CHAIN)));
    }

    private State tickExplore(TaskContext context) {
        if (exploration == null) {
            var view = guard(context)
                    .planningView(context, context.serverSettings().pathing());
            exploration = new ExplorationPlanner(
                    view,
                    node(),
                    targetY,
                    heading,
                    bounds,
                    supports,
                    CostModel.DEFAULTS,
                    context.serverSettings().pathing().maxNodes());
        }
        ExplorationPlanner.Status result = exploration.step(searchBudget(context), System::nanoTime);
        if (result == ExplorationPlanner.Status.RUNNING) {
            setPhase(node().y() <= targetY ? "tunnel" : "descend");
            return State.WORKING;
        }
        if (result == ExplorationPlanner.Status.NO_ROUTE) {
            exploration = null;
            return retryPlan("friendai.mining.unreachable");
        }
        PathStep next = exploration.nextStep().orElseThrow();
        setPhase(phaseFor(next.move()));
        exploration = null;
        return executeEdge(context, next);
    }

    private State tickExecutor(TaskContext context) {
        PathExecutor.State state = executor.tick(context);
        if (state == PathExecutor.State.RUNNING) {
            return State.WORKING;
        }
        if (state == PathExecutor.State.FAILED) {
            brokenBlocks += executor.brokenBlocks();
            rememberWalked(executor.stepIndex());
            executor.stop(context);
            executor = null;
            activeSteps = List.of();
            search = null;
            exploration = null;
            return retryPlan("friendai.mining.unsafe_passage");
        }
        brokenBlocks += executor.brokenBlocks();
        executor.stop(context);
        executor = null;
        rememberWalked(activeSteps.size());
        for (PathStep completed : activeSteps) {
            if (mode == Mode.EXPLORE
                    && completed.to().y() <= targetY
                    && Math.abs(completed.from().x() - completed.to().x())
                                    + Math.abs(completed.from().z()
                                            - completed.to().z())
                            == 1) {
                completedExplorationSteps++;
            }
        }
        noProgressPlans = 0;
        excludedStances.clear();
        activeSteps = List.of();
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
            return retryPlan(
                    target == null
                            ? UserMessage.of("friendai.mining.unreachable")
                            : guard(context).targetFailure(context, target));
        }
        BreakAction.State result = extraction.tick(context, matches);
        if (result == BreakAction.State.WORKING) {
            setPhase("extract");
            return State.WORKING;
        }
        MiningRefusal refusal = extraction.refusal();
        extraction.stop(context);
        extraction = null;
        if (result == BreakAction.State.BROKEN) {
            noProgressPlans = 0;
            search = null;
            return lastState = State.EXTRACTED;
        }
        if (target != null) {
            if (result == BreakAction.State.BLOCKED) {
                excludedStances.add(node());
            } else {
                excludedTargets.add(plain(target));
            }
        }
        target = null;
        search = null;
        return retryPlan(
                result == BreakAction.State.BLOCKED
                        ? ownerMessage(refusal)
                        : UserMessage.of("friendai.mining.unreachable"));
    }

    /** A translated sentence for the owner, one per typed refusal; the model gets the English one elsewhere. */
    private static UserMessage ownerMessage(MiningRefusal refusal) {
        if (refusal == null) {
            return UserMessage.of("friendai.mining.unsafe_support");
        }
        return switch (refusal) {
            case MiningRefusal.ServerProtected p -> UserMessage.of("friendai.mining.server_protected", coords(p.pos()));
            case MiningRefusal.PlaceProtected p -> UserMessage.of("friendai.mining.place_protected", coords(p.pos()));
            case MiningRefusal.OwnSupport p -> UserMessage.of("friendai.mining.own_support_unsafe", coords(p.pos()));
            case MiningRefusal.Blacklisted p -> UserMessage.of("friendai.mining.not_breakable", coords(p.pos()));
            case MiningRefusal.Unbreakable p -> UserMessage.of("friendai.mining.not_breakable", coords(p.pos()));
            case MiningRefusal.OutOfReach p -> UserMessage.of("friendai.mining.unreachable");
        };
    }

    private static String coords(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    private State executeEdge(TaskContext context, PathStep step) {
        return executeSteps(context, List.of(step));
    }

    /**
     * Walks a run of consecutive steps without planning in between. The executor still checks every step against the
     * live world, so a stale step fails and the next plan starts from where the companion stands.
     */
    private State executeSteps(TaskContext context, List<PathStep> steps) {
        long remaining = maxBlocks - brokenBlocks;
        List<PathStep> chain = new ArrayList<>();
        long breaks = 0;
        double cost = 0;
        for (PathStep step : steps) {
            if (breaks + step.breaks().size() > remaining) {
                break;
            }
            breaks += step.breaks().size();
            cost += step.cost();
            chain.add(step);
        }
        if (chain.isEmpty()) {
            return exhaust(context);
        }
        Path route = new Path(chain, cost, (int) breaks, 0, false);
        PathingConfig config = context.serverSettings().pathing().withActions(true, false);
        executor = new PathExecutor(
                route,
                new MaterialPicker(
                        config,
                        context.serverSettings().breakList(),
                        context.serverSettings().placeList()),
                config,
                guard(context),
                null,
                context.serverSettings().breakList());
        activeSteps = List.copyOf(chain);
        search = null;
        return State.WORKING;
    }

    /** Keeps the floors of the first {@code count} steps of the running route, so they are never mined away. */
    private void rememberWalked(int count) {
        for (int i = 0; i < Math.min(count, activeSteps.size()); i++) {
            rememberSupport(activeSteps.get(i).from());
            rememberSupport(activeSteps.get(i).to());
        }
    }

    private void createTargetSearch(TaskContext context) {
        var view = guard(context).planningView(context, context.serverSettings().pathing());
        List<Pos> candidates = new ArrayList<>();
        for (BlockPos candidate : targets) {
            Pos plain = plain(candidate);
            if (!excludedTargets.contains(plain)) {
                candidates.add(plain);
            }
        }
        goal = new MiningGoal(
                candidates,
                closeApproach ? 1.5 : Approach.REACH,
                companion.getEyeHeight(),
                supports,
                excludedStances,
                closeApproach);
        MiningPathPolicy miningPolicy = new MiningPathPolicy(view, bounds, supports, CostModel.DEFAULTS);
        PathPolicy policy = (freshView, step) -> {
            if (mustRelocateFromSupport
                    && step.from().equals(initialNode)
                    && !step.breaks().isEmpty()) {
                return java.util.Optional.empty();
            }
            return miningPolicy.prepare(freshView, step);
        };
        search = new PathSearch(
                view,
                CostModel.DEFAULTS,
                goal,
                node(),
                new SearchOptions(context.serverSettings().pathing().maxNodes(), bounds.radius(), 0, 1),
                policy);
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
        return retryPlan(UserMessage.of(key));
    }

    private State retryPlan(UserMessage reason) {
        noProgressPlans++;
        if (primaryFailure == null) {
            primaryFailure = reason;
        }
        if ("friendai.mining.no_tool".equals(reason.key())) {
            toolFailure = reason;
        }
        search = null;
        exploration = null;
        if (noProgressPlans >= 3) {
            return fail(reason);
        }
        return State.WORKING;
    }

    private State fail(String key) {
        return fail(UserMessage.of(key));
    }

    private State fail(UserMessage reason) {
        // A missing tool is the actionable cause; retries after excluding that target only see "no candidates"
        // and would otherwise report the vague "unreachable" instead.
        failure = toolFailure != null ? toolFailure : primaryFailure != null ? primaryFailure : reason;
        companion.owner().ifPresent(player -> player.sendSystemMessage(Components.of(failure), true));
        lastState = State.FAILED;
        return lastState;
    }

    private State exhaust(TaskContext context) {
        failure = UserMessage.of("friendai.mining.search_limit");
        companion.owner().ifPresent(player -> player.sendSystemMessage(Components.of(failure), true));
        lastState = State.EXHAUSTED;
        stop(context);
        return lastState;
    }

    private void setPhase(String next) {
        if (phase.equals(next)) {
            return;
        }
        phase = next;
        UserMessage message = UserMessage.of(
                switch (next) {
                    case "approach" -> "friendai.mining.phase.approach";
                    case "descend" -> "friendai.mining.phase.descend";
                    case "tunnel" -> "friendai.mining.phase.tunnel";
                    case "ascend" -> "friendai.mining.phase.ascend";
                    case "extract" -> "friendai.mining.phase.extract";
                    default -> "friendai.mining.phase.approach";
                });
        companion.owner().ifPresent(player -> player.sendSystemMessage(Components.of(message), true));
    }

    private long searchBudget(TaskContext context) {
        return Math.max(0, context.serverSettings().pathing().searchBudgetMs()) * 1_000_000L;
    }

    private ExcavationGuard guard(TaskContext context) {
        return new ExcavationGuard(bounds, supports);
    }

    private Node node() {
        BlockPos pos = companion.blockPosition();
        return new Node(pos.getX(), pos.getY(), pos.getZ());
    }

    /** The steps of the route the running executor walks; empty while none runs. */
    private List<PathStep> activeSteps = List.of();

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
