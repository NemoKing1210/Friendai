package dev.friendai.task.obtain;

import dev.friendai.core.FriendaiServerState;
import dev.friendai.knowledge.search.ResourceSearchProfile;
import dev.friendai.knowledge.search.SearchCoverage;
import dev.friendai.knowledge.search.SearchHint;
import dev.friendai.knowledge.search.SearchPlanner;
import dev.friendai.knowledge.search.SearchSession;
import dev.friendai.knowledge.search.TargetKind;
import dev.friendai.pathing.Pos;
import dev.friendai.perception.resource.CaveEntranceObservation;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.debug.DebugAction;
import dev.friendai.debug.WorkCell;
import dev.friendai.debug.WorkKind;
import dev.friendai.debug.WorkSet;
import dev.friendai.debug.WorkStatus;
import dev.friendai.task.nav.ResourceJourney;
import dev.friendai.task.builtin.ExploreTask;
import dev.friendai.task.mining.BlockMatcher;
import dev.friendai.task.nav.ResourceJourney;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;

/**
 * Purposeful search over a deterministic set of local viewpoints. The target kind decides what counts as found;
 * terrain, trail, return cost and an optional model hint decide where to go next. Resource identity still comes
 * from sight only.
 */
public final class ResourceSearchDriver implements Task {
    private static final int LOCAL_INSPECT_TICKS = 40;
    private static final int ARRIVAL_WAIT_TICKS = 40;

    private final SearchSession session;
    private final TargetKind target;
    private final ResourceSearchProfile profile;
    private final int radius;
    private final boolean expedition;
    private final ResourceSearchProfile.Strategy selectedStrategy;
    private final String requestedHeading;
    private final CaveEntranceObservation caveEntrance;
    private final SearchHint hint;
    private final SearchPlanner planner = new SearchPlanner();
    private final long startTravel;
    private final long startTicks;
    private final int startExcavation;
    private long foundCheckedTick = Long.MIN_VALUE;
    private boolean foundCached;
    private ResourceJourney journey;
    private Pos origin;
    private Pos lastPosition;
    private boolean returning;
    private boolean found;
    private boolean waiting;
    private boolean hintLogged;
    private boolean statsRecorded;
    private String summary = "search has not started";
    private int inspected;
    private ExploreTask miningSearch;
    private int accountedExcavation;
    private String startupFailure;
    private SearchPlanner.Frontier activeFrontier;
    private boolean caveScanning;
    private int caveScanTicks;
    private int localWaitTicks;
    private int arrivalWaitTicks;
    private boolean awaitingArrival;
    private SearchPlanner.Frontier pendingFrontier;

    public ResourceSearchDriver(
            SearchSession session,
            TargetKind target,
            ResourceSearchProfile profile,
            int radius,
            boolean expedition,
            ResourceSearchProfile.Strategy selectedStrategy,
            String requestedHeading,
            CaveEntranceObservation caveEntrance,
            SearchHint hint) {
        this(
                session,
                target,
                profile,
                radius,
                expedition,
                selectedStrategy,
                requestedHeading,
                caveEntrance,
                hint,
                false);
    }

    public ResourceSearchDriver(
            SearchSession session,
            TargetKind target,
            ResourceSearchProfile profile,
            int radius,
            boolean expedition,
            ResourceSearchProfile.Strategy selectedStrategy,
            String requestedHeading,
            CaveEntranceObservation caveEntrance,
            SearchHint hint,
            boolean initialFound) {
        this.session = session;
        this.target = target;
        this.profile = profile;
        this.radius = Math.max(16, radius);
        this.expedition = expedition;
        this.selectedStrategy = selectedStrategy;
        this.requestedHeading = requestedHeading;
        this.caveEntrance = caveEntrance;
        this.hint = hint;
        var snapshot = session.snapshot();
        this.startTravel = Math.round(snapshot.spentTravelBlocks());
        this.startTicks = snapshot.spentTicks();
        this.startExcavation = snapshot.excavationBlocks();
        this.found = initialFound;
    }

    @Override
    public String type() {
        return "resource_search_driver";
    }

    @Override
    public boolean hasTimeout() {
        return false;
    }

    @Override
    public void start(TaskContext context) {
        origin = current(context);
        lastPosition = origin;
        session.advance(input(context, null, SearchSession.LegOutcome.NONE, false, 0));
        if (selectedStrategy == ResourceSearchProfile.Strategy.SURFACE) {
            localWaitTicks = 0;
            FriendaiServerState.get(context.level().getServer())
                    .observations()
                    .observer(context.companion())
                    .scheduleSweep(logicalTick(context));
        }
        if (selectedStrategy == ResourceSearchProfile.Strategy.CAVE) {
            if (caveEntrance == null
                    || !caveEntrance
                            .dimension()
                            .equals(context.level().dimension().identifier().toString())) {
                startupFailure = "observed cave entrance is unavailable in this dimension";
                session.interrupt();
            } else {
                Pos interior = caveEntrance.interior();
                activeFrontier = new SearchPlanner.Frontier(
                        interior,
                        caveEntrance.approach(),
                        ResourceSearchProfile.Strategy.CAVE,
                        1,
                        Math.max(1, Math.round(distance(origin, interior) * 2)));
                session.advance(new SearchSession.Input(
                        logicalTick(context),
                        caveEntrance.dimension(),
                        origin,
                        true,
                        false,
                        SearchSession.LegOutcome.NONE,
                        activeFrontier,
                        0,
                        0,
                        0,
                        activeFrontier.estimatedTravelTicks()));
                journey = new ResourceJourney(
                        context,
                        caveEntrance.dimension(),
                        interior,
                        session,
                        FriendaiServerState.get(context.level().getServer())
                                .knowledge()
                                .book(context.companion().ownerUuid())
                                .trails(),
                        expedition);
            }
        } else if (selectedStrategy == ResourceSearchProfile.Strategy.MINE) {
            SearchPlanner.Frontier initial = frontier(origin.x(), origin.y(), origin.z());
            session.advance(new SearchSession.Input(
                    logicalTick(context),
                    context.level().dimension().identifier().toString(),
                    origin,
                    true,
                    false,
                    SearchSession.LegOutcome.NONE,
                    initial,
                    0,
                    0,
                    0,
                    0));
            int remainingExcavation = Math.max(
                    0,
                    session.snapshot().limits().excavationBlocks()
                            - session.snapshot().excavationBlocks());
            if (remainingExcavation == 0) {
                startupFailure = "search excavation budget is already spent";
                session.interrupt();
            } else {
                miningSearch = new ExploreTask(
                        targetMatcher(),
                        true,
                        profile.shallowStone()
                                ? origin.y() - 8
                                : profile.targetY().orElse(context.level().getMinY() + 16),
                        session.snapshot().limits().radius(),
                        remainingExcavation,
                        (int) Math.min(
                                Integer.MAX_VALUE,
                                session.snapshot().limits().ticks()
                                        - session.snapshot().spentTicks()));
                miningSearch.start(context);
            }
        }
        summary = diagnostic("searching for " + target.label() + " within " + radius + " blocks");
    }

    /** Freezes the search at a safe point while the owner catches up; the summary says why. */
    public void holdForOwner(TaskContext context, String reason) {
        if (journey != null) {
            journey.stop(context);
            journey = null;
        }
        if (miningSearch != null) {
            miningSearch.stop(context);
            miningSearch = null;
        }
        context.companion().getNavigation().stop();
        activeFrontier = null;
        awaitingArrival = false;
        waiting = true;
        summary = diagnostic(reason);
    }

    public void resumeAfterOwner() {
        waiting = false;
    }

    public boolean holding() {
        return waiting;
    }

    public TargetKind target() {
        return target;
    }

    @Override
    public List<WorkSet> debugWorkSets() {
        SearchSession.Snapshot snapshot = session.snapshot();
        List<WorkCell> cells = new ArrayList<>();
        Pos base = activeFrontier == null ? pendingFrontier == null ? snapshot.origin() : pendingFrontier.position()
                : activeFrontier.position();
        cells.add(new WorkCell(base.x(), base.y(), base.z(), DebugAction.USE, WorkStatus.NEXT,
                "Inspect " + target.label()));
        if (origin != null && (origin.x() != base.x() || origin.y() != base.y() || origin.z() != base.z())) {
            cells.add(new WorkCell(origin.x(), origin.y(), origin.z(), DebugAction.USE, WorkStatus.DONE,
                    "Search origin"));
        }
        return List.of(new WorkSet("Search for " + target.label(), WorkKind.SECTORS, cells.size(),
                cells.size() - 1, 1, 0, 0, false, cells));
    }

    /** Whether this search has already seen the target; persisted when an expedition walks home. */
    public boolean discovered() {
        return found;
    }

    @Override
    public TaskStatus tick(TaskContext context) {
        var state = FriendaiServerState.get(context.level().getServer());
        Pos position = current(context);
        if (!waiting) {
            double moved = distance(lastPosition, position);
            lastPosition = position;
            boolean budgetReturn = session.accountWork(logicalTick(context), position, moved, 0);
            return advance(context, state, position, budgetReturn);
        }
        return TaskStatus.RUNNING;
    }

    private TaskStatus advance(TaskContext context, FriendaiServerState state, Pos position, boolean budgetReturn) {
        if (startupFailure != null) {
            return fail(context, startupFailure);
        }
        if (!returning && awaitingArrival) {
            state.observations().observer(context.companion()).scheduleSweep(logicalTick(context));
            if (targetFound(context)) {
                awaitingArrival = false;
            } else if (++arrivalWaitTicks <= ARRIVAL_WAIT_TICKS) {
                return TaskStatus.RUNNING;
            } else {
                awaitingArrival = false;
                Pos approach = pendingFrontier == null ? position : pendingFrontier.approach();
                state.knowledge()
                        .book(context.companion().ownerUuid())
                        .recordCoverage(
                                context.level().dimension().identifier().toString(),
                                approach,
                                profile.family(),
                                strategy(),
                                SearchCoverage.Outcome.INSPECTED,
                                logicalTick(context));
                activeFrontier = null;
                inspected++;
                session.advance(input(context, null, SearchSession.LegOutcome.ARRIVED, false, 0));
            }
        }
        if (!returning
                && selectedStrategy == ResourceSearchProfile.Strategy.SURFACE
                && journey == null
                && miningSearch == null
                && ++localWaitTicks <= LOCAL_INSPECT_TICKS) {
            state.observations().observer(context.companion()).scheduleSweep(logicalTick(context));
            return TaskStatus.RUNNING;
        }
        if (!returning && targetFound(context)) {
            awaitingArrival = false;
            found = true;
            session.advance(input(context, null, SearchSession.LegOutcome.NONE, true, 0));
            if (journey != null) {
                journey.stop(context);
                journey = null;
            }
            if (miningSearch != null) {
                miningSearch.stop(context);
                miningSearch = null;
            }
            if (!expedition) return finishOnSight(context);
            returning = true;
            if (distance(position, origin) <= 2) return finishReturn(context);
            journey = new ResourceJourney(
                    context,
                    context.level().dimension().identifier().toString(),
                    origin,
                    session,
                    state.knowledge().book(context.companion().ownerUuid()).trails(),
                    expedition);
            return TaskStatus.RUNNING;
        }
        if (!returning && budgetReturn && journey != null) {
            journey.stop(context);
            journey = null;
            returning = true;
            if (distance(position, origin) <= 2) return finishReturn(context);
            journey = new ResourceJourney(
                    context,
                    context.level().dimension().identifier().toString(),
                    origin,
                    session,
                    state.knowledge().book(context.companion().ownerUuid()).trails(),
                    expedition);
            return TaskStatus.RUNNING;
        }
        if (!returning && budgetReturn && miningSearch != null) {
            miningSearch.stop(context);
            miningSearch = null;
            returning = true;
            summary = diagnostic("mining reserve reached; stopping excavation and returning");
            if (distance(position, origin) <= 2) return finishReturn(context);
            journey = new ResourceJourney(
                    context,
                    context.level().dimension().identifier().toString(),
                    origin,
                    session,
                    state.knowledge().book(context.companion().ownerUuid()).trails(),
                    expedition);
            return TaskStatus.RUNNING;
        }
        if (journey != null) {
            ResourceJourney.State travel = journey.tick(context);
            if (travel == ResourceJourney.State.BLOCKED || travel == ResourceJourney.State.CANCELLED) {
                journey.stop(context);
                journey = null;
                if (returning) {
                    session.interrupt();
                    return fail(context, "return route blocked; expedition stopped and its chunk lease was released");
                }
                state.knowledge()
                        .book(context.companion().ownerUuid())
                        .recordCoverage(
                                context.level().dimension().identifier().toString(),
                                activeFrontier == null ? position : activeFrontier.approach(),
                                profile.family(),
                                strategy(),
                                SearchCoverage.Outcome.BLOCKED,
                                logicalTick(context));
                activeFrontier = null;
                session.advance(input(context, null, SearchSession.LegOutcome.BLOCKED, false, 0));
                summary = diagnostic("frontier blocked; selecting another observed route");
            } else if (travel == ResourceJourney.State.ARRIVED) {
                journey.stop(context);
                journey = null;
                if (returning) return finishReturn(context);
                if (selectedStrategy == ResourceSearchProfile.Strategy.CAVE) {
                    caveScanning = true;
                    caveScanTicks = 0;
                    summary = diagnostic("inspecting the observed cave passage by direct sight");
                    return TaskStatus.RUNNING;
                }
                if (selectedStrategy == ResourceSearchProfile.Strategy.SURFACE) {
                    awaitingArrival = true;
                    pendingFrontier = activeFrontier;
                    arrivalWaitTicks = 0;
                    state.observations().observer(context.companion()).scheduleSweep(logicalTick(context));
                    summary = diagnostic("inspecting the reached surface viewpoint by direct sight");
                    return TaskStatus.RUNNING;
                }
                state.observations().observer(context.companion()).scheduleSweep(logicalTick(context));
                state.knowledge()
                        .book(context.companion().ownerUuid())
                        .recordCoverage(
                                context.level().dimension().identifier().toString(),
                                activeFrontier == null ? position : activeFrontier.approach(),
                                profile.family(),
                                strategy(),
                                SearchCoverage.Outcome.INSPECTED,
                                logicalTick(context));
                activeFrontier = null;
                inspected++;
                if (targetFound(context)) found = true;
                session.advance(input(context, null, SearchSession.LegOutcome.ARRIVED, false, 0));
            } else return TaskStatus.RUNNING;
        }

        if (miningSearch != null) {
            TaskStatus miningStatus = miningSearch.tick(context);
            int excavation = miningSearch.excavationBlocks();
            int newlyExcavated = Math.max(0, excavation - accountedExcavation);
            accountedExcavation = excavation;
            boolean exhausted = session.accountWork(logicalTick(context), current(context), 0, newlyExcavated);
            if (exhausted) {
                miningSearch.stop(context);
                miningSearch = null;
                returning = true;
                summary = diagnostic("mining budget reached; stopping excavation and returning");
                if (distance(current(context), origin) <= 2) return finishReturn(context);
                journey = new ResourceJourney(
                        context,
                        context.level().dimension().identifier().toString(),
                        origin,
                        session,
                        state.knowledge().book(context.companion().ownerUuid()).trails(),
                        expedition);
                return TaskStatus.RUNNING;
            }
            if (miningStatus == TaskStatus.RUNNING) return TaskStatus.RUNNING;
            miningSearch.stop(context);
            miningSearch = null;
            found = miningStatus == TaskStatus.SUCCESS && targetFound(context);
            session.advance(input(context, null, SearchSession.LegOutcome.ARRIVED, found, 0));
            if (!found) {
                summary = diagnostic("guarded mining leg ended without current visible evidence for " + target.label());
                session.advance(input(context, null, SearchSession.LegOutcome.NONE, false, 0));
                session.advance(input(context, null, SearchSession.LegOutcome.NONE, false, 0));
                session.advance(input(context, null, SearchSession.LegOutcome.NONE, false, 0));
                if (distance(position, origin) <= 2) return fail(context, summary);
                returning = true;
                journey = new ResourceJourney(
                        context,
                        context.level().dimension().identifier().toString(),
                        origin,
                        session,
                        state.knowledge().book(context.companion().ownerUuid()).trails(),
                        expedition);
                return TaskStatus.RUNNING;
            }
            if (!expedition) return finishOnSight(context);
            returning = true;
            if (distance(position, origin) <= 2) return finishReturn(context);
            journey = new ResourceJourney(
                    context,
                    context.level().dimension().identifier().toString(),
                    origin,
                    session,
                    state.knowledge().book(context.companion().ownerUuid()).trails(),
                    expedition);
            return TaskStatus.RUNNING;
        }

        if (!returning && (budgetReturn || found || session.snapshot().phase() == SearchSession.Phase.RETURN)) {
            returning = true;
            if (distance(current(context), origin) <= 2) return finishReturn(context);
            journey = new ResourceJourney(
                    context,
                    context.level().dimension().identifier().toString(),
                    origin,
                    session,
                    state.knowledge().book(context.companion().ownerUuid()).trails(),
                    expedition);
            return TaskStatus.RUNNING;
        }
        if (returning) return TaskStatus.RUNNING;

        if (caveScanning) {
            caveScanTicks++;
            state.observations().observer(context.companion()).scheduleSweep(logicalTick(context));
            if (caveScanTicks < 240 && !budgetReturn) return TaskStatus.RUNNING;
            caveScanning = false;
            session.advance(input(context, null, SearchSession.LegOutcome.NONE, false, 0));
            session.advance(input(context, null, SearchSession.LegOutcome.NONE, false, 0));
            session.advance(input(context, null, SearchSession.LegOutcome.NONE, false, 0));
            returning = true;
            summary = diagnostic("cave inspection budget ended; returning to origin");
            if (distance(position, origin) <= 2) return finishReturn(context);
            journey = new ResourceJourney(
                    context,
                    context.level().dimension().identifier().toString(),
                    origin,
                    session,
                    state.knowledge().book(context.companion().ownerUuid()).trails(),
                    expedition);
            return TaskStatus.RUNNING;
        }

        if (session.snapshot().phase() == SearchSession.Phase.INSPECT) {
            session.advance(input(context, null, SearchSession.LegOutcome.NONE, false, 0));
        }
        if (session.snapshot().phase() == SearchSession.Phase.EVALUATE) {
            if (targetFound(context)) found = true;
            session.advance(input(context, null, SearchSession.LegOutcome.NONE, false, 0));
            if (found) return TaskStatus.RUNNING;
        }
        if (journey == null) {
            ResourceSearchProfile.Strategy strategy = strategy();
            if (strategy != ResourceSearchProfile.Strategy.SURFACE) {
                session.interrupt();
                return fail(
                        context,
                        "no observed entrance or safe local route for "
                                + strategy.name().toLowerCase(Locale.ROOT));
            }
            long now = logicalTick(context);
            SearchPlanner.Request request = plannerRequest(context, state, now);
            List<SearchPlanner.Ranked> ranked = planner.rank(request);
            if (ranked.isEmpty()) {
                summary = diagnostic("no unvisited local frontier remains after " + inspected + " viewpoints");
                session.advance(input(context, null, SearchSession.LegOutcome.NONE, false, 0));
                if (distance(current(context), origin) <= 2) return fail(context, summary);
                returning = true;
                journey = new ResourceJourney(
                        context,
                        context.level().dimension().identifier().toString(),
                        origin,
                        session,
                        state.knowledge().book(context.companion().ownerUuid()).trails(),
                        expedition);
                return TaskStatus.RUNNING;
            }
            SearchPlanner.Frontier frontier = ranked.get(0).frontier();
            explainChoice(context, ranked, now);
            session.advance(new SearchSession.Input(
                    now,
                    context.level().dimension().identifier().toString(),
                    position,
                    true,
                    false,
                    SearchSession.LegOutcome.NONE,
                    frontier,
                    0,
                    0,
                    0,
                    frontier.estimatedTravelTicks()));
            activeFrontier = frontier;
            journey = new ResourceJourney(
                    context,
                    context.level().dimension().identifier().toString(),
                    frontier.position(),
                    session,
                    state.knowledge().book(context.companion().ownerUuid()).trails(),
                    expedition);
        }
        return TaskStatus.RUNNING;
    }

    private SearchPlanner.Request plannerRequest(TaskContext context, FriendaiServerState state, long now) {
        long remaining = Math.max(
                0, session.snapshot().limits().ticks() - session.snapshot().spentTicks());
        SearchHint live = hint;
        var owner = context.companion().ownerUuid();
        if (owner != null) {
            live = FriendaiServerState.get(context.level().getServer())
                    .searchHints()
                    .get(owner, now)
                    .orElse(null);
        }
        return new SearchPlanner.Request(
                context.level().dimension().identifier().toString(),
                origin,
                frontiers(context),
                profile,
                state.knowledge().book(context.companion().ownerUuid()).searchCoverage(),
                remaining,
                now,
                heading(),
                Optional.ofNullable(live).filter(value -> !value.expired(now)));
    }

    private void explainChoice(TaskContext context, List<SearchPlanner.Ranked> ranked, long now) {
        SearchHint live = hint;
        var owner = context.companion().ownerUuid();
        if (owner != null) {
            live = FriendaiServerState.get(context.level().getServer())
                    .searchHints()
                    .get(owner, now)
                    .orElse(null);
        }
        if (!hintLogged && live != null && !live.expired(now)) {
            context.companion().taskRunner().debug("search hint: " + live.describe(now));
            hintLogged = true;
        }
        StringBuilder line = new StringBuilder("search chose ").append(describe(ranked.get(0)));
        for (int i = 1; i < Math.min(4, ranked.size()); i++) {
            line.append("; rejected ").append(describe(ranked.get(i)));
        }
        context.companion().taskRunner().debug(line.toString());
    }

    private static String describe(SearchPlanner.Ranked ranked) {
        var frontier = ranked.frontier();
        var terrain = frontier.terrain();
        return frontier.approach().x() + " " + frontier.approach().z() + " (score "
                + String.format(Locale.ROOT, "%.1f", ranked.score())
                + (terrain.biomeTransition() ? ", biome edge" : "")
                + (terrain.onTrail() ? ", known trail" : "")
                + (terrain.waterFraction() > 0 ? ", water " + Math.round(terrain.waterFraction() * 100) + "%" : "")
                + (terrain.hazardFraction() > 0 ? ", hazard " + Math.round(terrain.hazardFraction() * 100) + "%" : "")
                + ")";
    }

    /**
     * A local search ends where the target was seen: the caller walks to it from here, and a trip back to the
     * start would only make it cross the same ground twice. Expeditions still return, to release their chunks.
     */
    private TaskStatus finishOnSight(TaskContext context) {
        journey = null;
        found = true;
        session.interrupt();
        recordStats(context, true);
        summary = diagnostic("found " + target.label() + " by direct sight after " + inspected + " viewpoints");
        return TaskStatus.SUCCESS;
    }

    private TaskStatus finishReturn(TaskContext context) {
        journey = null;
        if (found) {
            session.advance(input(context, null, SearchSession.LegOutcome.NONE, false, 0));
            recordStats(context, true);
            summary = diagnostic("found " + target.label() + " by direct sight after " + inspected
                    + " viewpoints; returned to origin");
            return TaskStatus.SUCCESS;
        }
        recordStats(context, false);
        summary = diagnostic(
                "no directly visible " + target.label() + " after " + inspected + " viewpoints; returned to origin");
        return TaskStatus.FAILED;
    }

    private TaskStatus fail(TaskContext context, String reason) {
        recordStats(context, false);
        summary = reason.startsWith("phase=") ? reason : diagnostic(reason);
        return TaskStatus.FAILED;
    }

    private void recordStats(TaskContext context, boolean discovered) {
        if (statsRecorded) return;
        statsRecorded = true;
        var owner = context.companion().ownerUuid();
        if (owner == null) return;
        var snapshot = session.snapshot();
        var stats = FriendaiServerState.get(context.level().getServer())
                .knowledge()
                .book(owner)
                .searchLedger()
                .stats();
        stats.record(
                target.coverageKey(),
                discovered,
                Math.max(0, snapshot.spentTravelBlocks() - startTravel),
                Math.max(0, snapshot.spentTicks() - startTicks),
                Math.max(0, snapshot.excavationBlocks() - startExcavation));
        String statsLine = stats.summary();
        if (!statsLine.isEmpty() && summary.length() + statsLine.length() < 580) {
            summary = summary + "; " + statsLine;
        }
    }

    private boolean targetFound(TaskContext context) {
        long tick = logicalTick(context);
        if (tick == foundCheckedTick) return foundCached;
        foundCheckedTick = tick;
        foundCached = SearchTargets.found(context.companion(), target, effectiveRadius());
        return foundCached;
    }

    /** The live session radius: an escort cap or a reload can shrink it after this driver was built. */
    private int effectiveRadius() {
        return Math.min(radius, session.snapshot().limits().radius());
    }

    private List<SearchPlanner.Frontier> frontiers(TaskContext context) {
        List<SearchPlanner.Frontier> result = new ArrayList<>();
        int steps = Math.max(1, effectiveRadius() / 16);
        var trails = FriendaiServerState.get(context.level().getServer())
                .knowledge()
                .book(context.companion().ownerUuid())
                .trails();
        for (int step = 1; step <= steps; step++) {
            int d = step * 16;
            result.add(frontier(context, trails, origin.x(), origin.y(), origin.z() - d));
            result.add(frontier(context, trails, origin.x() + d, origin.y(), origin.z()));
            result.add(frontier(context, trails, origin.x(), origin.y(), origin.z() + d));
            result.add(frontier(context, trails, origin.x() - d, origin.y(), origin.z()));
        }
        return result;
    }

    private SearchPlanner.Frontier frontier(
            TaskContext context, dev.friendai.knowledge.travel.TrailGraph trails, int x, int y, int z) {
        Pos point = new Pos(x, y, z);
        var terrain = SearchFrontierFacts.sample(context.companion(), origin, point, trails);
        return new SearchPlanner.Frontier(
                point, point, strategy(), 1, Math.max(1, Math.round(distance(origin, point) * 2)), terrain);
    }

    /** A frontier at the companion's own position, used only for the initial mining leg. */
    private SearchPlanner.Frontier frontier(int x, int y, int z) {
        Pos point = new Pos(x, y, z);
        return new SearchPlanner.Frontier(
                point, point, strategy(), 1, Math.max(1, Math.round(distance(origin, point) * 2)));
    }

    private ResourceSearchProfile.Strategy strategy() {
        return selectedStrategy;
    }

    private BlockMatcher targetMatcher() {
        Set<String> blockIds = target instanceof TargetKind.BlockFamily family ? family.blockIds() : Set.of();
        return new BlockMatcher() {
            @Override
            public boolean test(net.minecraft.world.level.block.state.BlockState state) {
                return blockIds.contains(
                        BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
            }

            @Override
            public String label() {
                return target.label();
            }
        };
    }

    private SearchPlanner.Heading heading() {
        if (requestedHeading == null || requestedHeading.isBlank()) return SearchPlanner.Heading.NORTH;
        try {
            return SearchPlanner.Heading.valueOf(requestedHeading.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return SearchPlanner.Heading.NORTH;
        }
    }

    private String diagnostic(String reason) {
        SearchSession.Snapshot snapshot = session.snapshot();
        long remainingTicks = Math.max(0, snapshot.limits().ticks() - snapshot.spentTicks());
        double remainingTravel = Math.max(0, snapshot.limits().travelBlocks() - snapshot.spentTravelBlocks());
        int remainingExcavation = Math.max(0, snapshot.limits().excavationBlocks() - snapshot.excavationBlocks());
        String value = "phase=" + snapshot.phase().name().toLowerCase(Locale.ROOT)
                + "; strategy=" + strategy().name().toLowerCase(Locale.ROOT)
                + "; time=" + snapshot.spentTicks() + "/" + snapshot.limits().ticks()
                + " ticks (" + remainingTicks + " left); travel="
                + Math.round(snapshot.spentTravelBlocks()) + "/"
                + Math.round(snapshot.limits().travelBlocks())
                + " blocks (" + Math.round(remainingTravel) + " left); excavation="
                + snapshot.excavationBlocks() + "/" + snapshot.limits().excavationBlocks()
                + " blocks (" + remainingExcavation + " left); " + reason;
        return value.length() <= 600 ? value : value.substring(0, 597) + "...";
    }

    private SearchSession.Input input(
            TaskContext context,
            SearchPlanner.Frontier frontier,
            SearchSession.LegOutcome outcome,
            boolean discovered,
            long returnEstimate) {
        return new SearchSession.Input(
                logicalTick(context),
                context.level().dimension().identifier().toString(),
                current(context),
                true,
                discovered,
                outcome,
                frontier,
                0,
                0,
                0,
                returnEstimate);
    }

    @Override
    public void stop(TaskContext context) {
        if (journey != null) journey.stop(context);
        if (miningSearch != null) miningSearch.stop(context);
    }

    @Override
    public String describe() {
        return "searching for " + target.label();
    }

    @Override
    public String resultSummary() {
        return summary;
    }

    private static Pos current(TaskContext context) {
        var p = context.companion().blockPosition();
        return new Pos(p.getX(), p.getY(), p.getZ());
    }

    private static long logicalTick(TaskContext context) {
        return context.level().getServer().overworld().getGameTime();
    }

    private static double distance(Pos a, Pos b) {
        double dx = a.x() - (double) b.x(), dy = a.y() - (double) b.y(), dz = a.z() - (double) b.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
