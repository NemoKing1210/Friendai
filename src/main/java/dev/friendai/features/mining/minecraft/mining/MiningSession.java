package dev.friendai.features.mining.minecraft.mining;

import dev.friendai.common.Components;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.features.mining.DropRisk;
import dev.friendai.features.mining.ExplorationPlanner;
import dev.friendai.features.mining.MiningBounds;
import dev.friendai.features.mining.MiningGoal;
import dev.friendai.features.mining.MiningPathPolicy;
import dev.friendai.features.navigation.minecraft.nav.Approach;
import dev.friendai.features.navigation.minecraft.nav.MaterialPicker;
import dev.friendai.features.navigation.minecraft.nav.PathExecutor;
import dev.friendai.features.navigation.minecraft.nav.PlacementLedger;
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

    /** Ticks a fall is waited out before planning from mid-air, so a body that never lands still reports a failure. */
    private static final int MAX_AIRBORNE_TICKS = 40;

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
    private int airborneTicks;
    private boolean mustRelocateFromSupport;
    private boolean closeApproach;
    private PathSearch search;
    private MiningGoal goal;
    private ExplorationPlanner exploration;
    private PathExecutor executor;
    private BreakAction extraction;
    /** The catch platform under the target while it is built, and the ledger that records its blocks. */
    private CatchPlatform platform;

    private boolean catchChecked;
    private final PlacementLedger placements;
    private BlockPos target;
    private State lastState = State.WORKING;
    private UserMessage failure;
    /** The first concrete refusal this session hit, kept when later attempts have nothing left to try. */
    private UserMessage primaryFailure;
    /** A refusal caused by the wrong tool; it must not be replaced by the generic "unreachable" that follows. */
    private UserMessage toolFailure;

    private String phase = "";
    /** Which plan attempt last gave up, for the debug line; empty while the session is getting on with the job. */
    private String diagnostic = "";

    public MiningSession(CompanionEntity companion, MiningBounds bounds, int maxBlocks, int maxTicks) {
        this(companion, bounds, maxBlocks, maxTicks, new PlacementLedger());
    }

    /**
     * @param placements the caller's ledger, so a catch platform built under a target is taken down by the same task
     */
    public MiningSession(
            CompanionEntity companion, MiningBounds bounds, int maxBlocks, int maxTicks, PlacementLedger placements) {
        if (maxBlocks < 0 || maxTicks < 1) {
            throw new IllegalArgumentException("Mining limits must be non-negative blocks and positive ticks");
        }
        this.companion = companion;
        this.bounds = bounds;
        this.maxBlocks = maxBlocks;
        this.maxTicks = maxTicks;
        this.placements = placements;
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
        this.diagnostic = "";
        this.airborneTicks = 0;
        this.lastState = State.WORKING;
        this.search = null;
        this.goal = null;
        this.exploration = null;
        this.excludedTargets.clear();
        this.excludedStances.clear();
        this.mustRelocateFromSupport =
                this.targets.stream().anyMatch(pos -> pos.equals(blockPos(initialNode.support())));
        this.closeApproach = false;
        this.remainingSteps = List.of();
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
        this.diagnostic = "";
        this.lastState = State.WORKING;
        this.search = null;
        this.goal = null;
        this.exploration = null;
        this.remainingSteps = List.of();
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
        if (mode == Mode.TARGETS && waitForGround()) {
            return State.WORKING;
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

    /** The catch cells being placed under the current target, for the debug overlay; empty otherwise. */
    public List<net.minecraft.core.BlockPos> catchCells() {
        return platform == null ? List.of() : platform.cells();
    }

    public String phase() {
        return phase;
    }

    /**
     * One line on why the last plans gave up, for the debug status and a failing test's trace: which refusal fired,
     * how many plans it took to reach the limit, how many candidates are left, and what the companion is standing in.
     * A session that gives up within a few ticks never sets a phase, so this is the only account of what happened.
     */
    public String debugReason() {
        if (diagnostic.isEmpty() && primaryFailure == null) {
            return "";
        }
        BlockPos at = companion.blockPosition();
        String reason = diagnostic.isEmpty() ? shortKey(primaryFailure.key()) : diagnostic;
        return "why=" + reason
                + " x" + noProgressPlans
                + " cand=" + targets.size()
                + " ex=" + excludedTargets.size() + "/" + excludedStances.size()
                + " feet=" + cell(at)
                + " head=" + cell(at.above())
                + " below=" + cell(at.below());
    }

    private static String shortKey(String key) {
        int dot = key.lastIndexOf('.');
        return dot < 0 ? key : key.substring(dot + 1);
    }

    private String cell(BlockPos pos) {
        return companion.level().getBlockState(pos).isAir() ? "air" : "solid";
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

    /**
     * Every cell the planned passage still has to break, the running chain first, for the debug overlay: the owner
     * sees the corridor of the whole plan instead of only the step under execution.
     */
    public List<Pos> plannedBreaks() {
        List<Pos> cells = new ArrayList<>();
        for (PathStep step : activeSteps) {
            cells.addAll(step.breaks());
        }
        for (PathStep step : remainingSteps) {
            cells.addAll(step.breaks());
        }
        return cells;
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
        if (platform != null) {
            platform.stop();
            platform = null;
        }
        search = null;
        exploration = null;
        activeSteps = List.of();
        remainingSteps = List.of();
    }

    private State tickTargets(TaskContext context) {
        if (targets.isEmpty()) {
            return fail("friendai.mining.unreachable");
        }
        State resumed = continueRemaining(context);
        if (resumed != null) {
            return resumed;
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
                diagnostic = "no-path-close";
                return State.WORKING;
            }
            diagnostic = "no-path";
            return retryPlan("friendai.mining.unreachable");
        }
        Path passage = path.passagePrefix().orElse(null);
        if (passage == null) {
            // A partial route that places a block is a stair cut short; the route that would reach is planned from
            // the material the companion carries, so search again rather than spend the block on half a rise.
            diagnostic = "cut-passage";
            return retryPlan("friendai.mining.unreachable");
        }
        Node current = node();
        if (passage.steps().isEmpty()) {
            var visibleTarget = goal.targetAt(current, guard(context).planningView(context, context.pathingConfig()));
            if (visibleTarget.isEmpty()) {
                excludedStances.add(current);
                diagnostic = "blind-stance";
                return retryPlan("friendai.mining.unreachable");
            }
            target = blockPos(visibleTarget.get());
            setPhase("extract");
            search = null;
            catchChecked = false;
            if (!guard(context).allowTarget(context, target)) {
                UserMessage reason = guard(context).targetFailure(context, target);
                if (reason.key().equals("friendai.mining.no_tool")
                        || reason.key().equals("friendai.mining.unsafe_passage")
                        || !companion.hands().canInteract(target)) {
                    excludedTargets.add(plain(target));
                } else {
                    excludedStances.add(current);
                }
                diagnostic = "refused-" + shortKey(reason.key());
                return retryPlan(reason);
            }
            extraction = new BreakAction(target);
            return State.WORKING;
        }
        return executeSteps(context, passage.steps());
    }

    /**
     * Walks the rest of the passage already planned instead of searching for it again. Only when its first step still
     * starts where the companion stands; the executor checks every step against the live world and a stale step
     * fails the run, which drops the rest.
     */
    private State continueRemaining(TaskContext context) {
        if (remainingSteps.isEmpty()) {
            return null;
        }
        if (!remainingSteps.get(0).from().equals(node())) {
            remainingSteps = List.of();
            return null;
        }
        return executeSteps(context, remainingSteps);
    }

    private State tickExplore(TaskContext context) {
        if (exploration == null) {
            var view = guard(context).planningView(context, context.pathingConfig());
            exploration = new ExplorationPlanner(
                    view,
                    node(),
                    targetY,
                    heading,
                    bounds,
                    supports,
                    CostModel.DEFAULTS,
                    context.pathingConfig().maxNodes());
        }
        ExplorationPlanner.Status result = exploration.step(searchBudget(context), System::nanoTime);
        if (result == ExplorationPlanner.Status.RUNNING) {
            setPhase(node().y() <= targetY ? "tunnel" : "descend");
            return State.WORKING;
        }
        if (result == ExplorationPlanner.Status.NO_ROUTE) {
            exploration = null;
            diagnostic = "no-route";
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
            remainingSteps = List.of();
            search = null;
            exploration = null;
            diagnostic = "walk-failed";
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
        diagnostic = "";
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
            UserMessage lost = target == null
                    ? UserMessage.of("friendai.mining.unreachable")
                    : guard(context).targetFailure(context, target);
            diagnostic = target == null ? "target-gone" : "target-refused-" + shortKey(lost.key());
            return retryPlan(lost);
        }
        if (!catchChecked) {
            catchChecked = true;
            DropGuard.Plan plan = DropGuard.plan(context, target, placements);
            if (plan.refusal() != null) {
                excludedTargets.add(plain(target));
                UserMessage refusal = ownerMessage(plan.refusal());
                if (extraction != null) {
                    extraction.stop(context);
                    extraction = null;
                }
                target = null;
                search = null;
                diagnostic = "catch-refused";
                return retryPlan(refusal);
            }
            platform = plan.platform();
        }
        if (platform != null) {
            CatchPlatform.State state = platform.tick(context);
            if (state == CatchPlatform.State.WORKING) {
                return State.WORKING;
            }
            CatchPlatform finished = platform;
            platform = null;
            if (state == CatchPlatform.State.REFUSED) {
                if (extraction != null) {
                    extraction.stop(context);
                    extraction = null;
                }
                MiningRefusal refusal = new MiningRefusal.DropUnprotected(
                        target, null, DropRisk.Reason.PROTECTED_CELL, finished.failure());
                excludedTargets.add(plain(target));
                target = null;
                search = null;
                diagnostic = "platform-refused";
                return retryPlan(ownerMessage(refusal));
            }
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
            diagnostic = "";
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
        diagnostic = result == BreakAction.State.BLOCKED ? "break-blocked" : "break-failed";
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
            case MiningRefusal.DropUnprotected p -> UserMessage.of("friendai.mining.drop_unprotected", coords(p.pos()));
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
     * live world, so a stale step fails and the next plan starts from where the companion stands. The steps beyond the
     * chain stay planned: the next tick continues the same passage instead of searching for the same route again.
     */
    private State executeSteps(TaskContext context, List<PathStep> steps) {
        long remaining = maxBlocks - brokenBlocks;
        List<PathStep> chain = new ArrayList<>();
        long breaks = 0;
        double cost = 0;
        int consumed = 0;
        for (PathStep step : steps) {
            if (chain.size() >= MAX_CHAIN || breaks + step.breaks().size() > remaining) {
                break;
            }
            breaks += step.breaks().size();
            cost += step.cost();
            chain.add(step);
            consumed++;
        }
        remainingSteps = List.copyOf(steps.subList(consumed, steps.size()));
        if (chain.isEmpty()) {
            remainingSteps = List.of();
            return exhaust(context);
        }
        Path route = new Path(chain, cost, (int) breaks, 0, false);
        PathingConfig config = context.pathingConfig().withActions(true, false);
        executor = new PathExecutor(
                route,
                new MaterialPicker(config, context.breakList(), context.placeList()),
                config,
                guard(context),
                null,
                context.breakList());
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
        var view = guard(context).planningView(context, context.pathingConfig());
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
                new SearchOptions(context.pathingConfig().maxNodes(), bounds.radius(), 0, 1, companion.canSprint()),
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

    /**
     * Whether a fall has to be waited out before planning. A body in the air has no stance of its own: nothing under
     * its feet holds it up, so every candidate is refused and three plans in a row turn a landing — a companion that
     * dropped into the hole it just opened — into "the resource cannot be reached safely". The wait is bounded, because
     * a body that never lands (a void, a long drop) still has to report something.
     */
    private boolean waitForGround() {
        if (companion.onGround()) {
            airborneTicks = 0;
            return false;
        }
        if (++airborneTicks > MAX_AIRBORNE_TICKS) {
            airborneTicks = 0;
            return false;
        }
        setPhase("settle");
        return true;
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
        remainingSteps = List.of();
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
        return Math.max(0, context.pathingConfig().searchBudgetMs()) * 1_000_000L;
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

    /** The planned steps after the running chain: the rest of the same passage, walked before planning again. */
    private List<PathStep> remainingSteps = List.of();

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
