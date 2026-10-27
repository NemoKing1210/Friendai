package dev.friendai.task.obtain;

import dev.friendai.core.FriendaiServerState;
import dev.friendai.knowledge.search.ResourceSearchProfile;
import dev.friendai.knowledge.search.SearchCoverage;
import dev.friendai.knowledge.search.SearchPlanner;
import dev.friendai.knowledge.search.SearchSession;
import dev.friendai.pathing.Pos;
import dev.friendai.perception.resource.CaveEntranceObservation;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.nav.ResourceJourney;
import dev.friendai.task.builtin.ExploreTask;
import dev.friendai.task.mining.BlockMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;

/** Purposeful surface search over a deterministic set of local viewpoints; resource identity comes from sight only. */
public final class ResourceSearchDriver implements Task {
    private static final int LOCAL_INSPECT_TICKS = 40;
    private static final int ARRIVAL_WAIT_TICKS = 40;

    private final SearchSession session;
    private final ResourceSearchProfile profile;
    private final Set<String> targetBlocks;
    private final int radius;
    private final boolean expedition;
    private final ResourceSearchProfile.Strategy selectedStrategy;
    private final String requestedHeading;
    private final CaveEntranceObservation caveEntrance;
    private final SearchPlanner planner = new SearchPlanner();
    private ResourceJourney journey;
    private Pos origin;
    private Pos lastPosition;
    private boolean returning;
    private boolean found;
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

    public ResourceSearchDriver(SearchSession session, ResourceSearchProfile profile, Set<String> targetBlocks, int radius,
            boolean expedition, ResourceSearchProfile.Strategy selectedStrategy, String requestedHeading,
            CaveEntranceObservation caveEntrance) {
        this.session = session;
        this.profile = profile;
        this.targetBlocks = Set.copyOf(targetBlocks);
        this.radius = Math.max(16, radius);
        this.expedition = expedition;
        this.selectedStrategy = selectedStrategy;
        this.requestedHeading = requestedHeading;
        this.caveEntrance = caveEntrance;
    }

    @Override public String type() { return "resource_search_driver"; }
    @Override public boolean hasTimeout() { return false; }

    @Override public void start(TaskContext context) {
        origin = current(context);
        lastPosition = origin;
        session.advance(input(context, null, SearchSession.LegOutcome.NONE, false, 0));
        if (selectedStrategy == ResourceSearchProfile.Strategy.SURFACE) {
            localWaitTicks = 0;
            FriendaiServerState.get(context.level().getServer()).observations()
                    .observer(context.companion()).scheduleSweep(logicalTick(context));
        }
        if (selectedStrategy == ResourceSearchProfile.Strategy.CAVE) {
            if (caveEntrance == null || !caveEntrance.dimension().equals(context.level().dimension().identifier().toString())) {
                startupFailure = "observed cave entrance is unavailable in this dimension";
                session.interrupt();
            } else {
                Pos interior = caveEntrance.interior();
                activeFrontier = new SearchPlanner.Frontier(interior, caveEntrance.approach(),
                        ResourceSearchProfile.Strategy.CAVE, 1,
                        Math.max(1, Math.round(distance(origin, interior) * 2)));
                session.advance(new SearchSession.Input(logicalTick(context), caveEntrance.dimension(), origin,
                        true, false, SearchSession.LegOutcome.NONE, activeFrontier, 0, 0, 0,
                        activeFrontier.estimatedTravelTicks()));
                journey = new ResourceJourney(context, caveEntrance.dimension(), interior, session,
                        FriendaiServerState.get(context.level().getServer()).knowledge()
                                .book(context.companion().ownerUuid()).trails(), expedition);
            }
        } else if (selectedStrategy == ResourceSearchProfile.Strategy.MINE) {
            SearchPlanner.Frontier initial = frontier(origin.x(), origin.y(), origin.z());
            session.advance(new SearchSession.Input(logicalTick(context),
                    context.level().dimension().identifier().toString(), origin, true, false,
                    SearchSession.LegOutcome.NONE, initial, 0, 0, 0, 0));
            int remainingExcavation = Math.max(0, session.snapshot().limits().excavationBlocks()
                    - session.snapshot().excavationBlocks());
            if (remainingExcavation == 0) {
                startupFailure = "search excavation budget is already spent";
                session.interrupt();
            } else {
                miningSearch = new ExploreTask(targetMatcher(), true,
                        profile.targetY().orElse(context.level().getMinY() + 16),
                        session.snapshot().limits().radius(), remainingExcavation,
                        (int) Math.min(Integer.MAX_VALUE,
                                session.snapshot().limits().ticks() - session.snapshot().spentTicks()));
                miningSearch.start(context);
            }
        }
        summary = diagnostic("searching " + profile.family() + " within " + radius + " blocks");
    }

    @Override public TaskStatus tick(TaskContext context) {
        var state = FriendaiServerState.get(context.level().getServer());
        Pos position = current(context);
        double moved = distance(lastPosition, position);
        lastPosition = position;
        boolean budgetReturn = session.accountWork(logicalTick(context), position, moved, 0);
        if (startupFailure != null) {
            summary = diagnostic(startupFailure);
            return TaskStatus.FAILED;
        }
        if (!returning && awaitingArrival) {
            state.observations().observer(context.companion()).scheduleSweep(logicalTick(context));
            if (targetVisible(context)) {
                awaitingArrival = false;
            } else if (++arrivalWaitTicks <= ARRIVAL_WAIT_TICKS) {
                return TaskStatus.RUNNING;
            } else {
                awaitingArrival = false;
                Pos approach = pendingFrontier == null ? position : pendingFrontier.approach();
                state.knowledge().book(context.companion().ownerUuid()).recordCoverage(
                        context.level().dimension().identifier().toString(), approach, profile.family(),
                        strategy(), SearchCoverage.Outcome.INSPECTED, logicalTick(context));
                activeFrontier = null;
                inspected++;
                session.advance(input(context, null, SearchSession.LegOutcome.ARRIVED, false, 0));
            }
        }
        if (!returning && selectedStrategy == ResourceSearchProfile.Strategy.SURFACE
                && journey == null && miningSearch == null && ++localWaitTicks <= LOCAL_INSPECT_TICKS) {
            state.observations().observer(context.companion()).scheduleSweep(logicalTick(context));
            return TaskStatus.RUNNING;
        }
        if (!returning && targetVisible(context)) {
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
            returning = true;
            if (distance(position, origin) <= 2) return finishReturn(context);
            journey = new ResourceJourney(context, context.level().dimension().identifier().toString(), origin,
                    session, state.knowledge().book(context.companion().ownerUuid()).trails(), expedition);
            return TaskStatus.RUNNING;
        }
        if (!returning && budgetReturn && journey != null) {
            journey.stop(context);
            journey = null;
            returning = true;
            if (distance(position, origin) <= 2) return finishReturn(context);
            journey = new ResourceJourney(context, context.level().dimension().identifier().toString(), origin,
                    session, state.knowledge().book(context.companion().ownerUuid()).trails(), expedition);
            return TaskStatus.RUNNING;
        }
        if (!returning && budgetReturn && miningSearch != null) {
            miningSearch.stop(context);
            miningSearch = null;
            returning = true;
            summary = diagnostic("mining reserve reached; stopping excavation and returning");
            if (distance(position, origin) <= 2) return finishReturn(context);
            journey = new ResourceJourney(context, context.level().dimension().identifier().toString(), origin,
                    session, state.knowledge().book(context.companion().ownerUuid()).trails(), expedition);
            return TaskStatus.RUNNING;
        }
        if (journey != null) {
            ResourceJourney.State travel = journey.tick(context);
            if (travel == ResourceJourney.State.BLOCKED || travel == ResourceJourney.State.CANCELLED) {
                journey.stop(context);
                journey = null;
                if (returning) {
                    session.interrupt();
                    summary = diagnostic("return route blocked; expedition stopped and its chunk lease was released");
                    return TaskStatus.FAILED;
                }
                state.knowledge().book(context.companion().ownerUuid()).recordCoverage(
                        context.level().dimension().identifier().toString(),
                        activeFrontier == null ? position : activeFrontier.approach(), profile.family(),
                        strategy(), SearchCoverage.Outcome.BLOCKED, logicalTick(context));
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
                state.knowledge().book(context.companion().ownerUuid()).recordCoverage(
                        context.level().dimension().identifier().toString(),
                        activeFrontier == null ? position : activeFrontier.approach(), profile.family(),
                        strategy(), SearchCoverage.Outcome.INSPECTED, logicalTick(context));
                activeFrontier = null;
                inspected++;
                if (targetVisible(context)) found = true;
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
                journey = new ResourceJourney(context, context.level().dimension().identifier().toString(), origin,
                        session, state.knowledge().book(context.companion().ownerUuid()).trails(), expedition);
                return TaskStatus.RUNNING;
            }
            if (miningStatus == TaskStatus.RUNNING) return TaskStatus.RUNNING;
            miningSearch.stop(context);
            miningSearch = null;
            found = miningStatus == TaskStatus.SUCCESS && targetVisible(context);
            session.advance(input(context, null, SearchSession.LegOutcome.ARRIVED, found, 0));
            if (!found) {
                summary = diagnostic("guarded mining leg ended without current visible evidence for " + profile.family());
                session.advance(input(context, null, SearchSession.LegOutcome.NONE, false, 0));
                session.advance(input(context, null, SearchSession.LegOutcome.NONE, false, 0));
                session.advance(input(context, null, SearchSession.LegOutcome.NONE, false, 0));
                if (distance(position, origin) <= 2) return TaskStatus.FAILED;
                returning = true;
                journey = new ResourceJourney(context, context.level().dimension().identifier().toString(), origin,
                        session, state.knowledge().book(context.companion().ownerUuid()).trails(), expedition);
                return TaskStatus.RUNNING;
            }
            returning = true;
            if (distance(position, origin) <= 2) return finishReturn(context);
            journey = new ResourceJourney(context, context.level().dimension().identifier().toString(), origin,
                    session, state.knowledge().book(context.companion().ownerUuid()).trails(), expedition);
            return TaskStatus.RUNNING;
        }

        if (!returning && (budgetReturn || found || session.snapshot().phase() == SearchSession.Phase.RETURN)) {
            returning = true;
            if (distance(current(context), origin) <= 2) return finishReturn(context);
            journey = new ResourceJourney(context, context.level().dimension().identifier().toString(), origin,
                    session, state.knowledge().book(context.companion().ownerUuid()).trails(), expedition);
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
            journey = new ResourceJourney(context, context.level().dimension().identifier().toString(), origin,
                    session, state.knowledge().book(context.companion().ownerUuid()).trails(), expedition);
            return TaskStatus.RUNNING;
        }

        if (session.snapshot().phase() == SearchSession.Phase.INSPECT) {
            session.advance(input(context, null, SearchSession.LegOutcome.NONE, false, 0));
        }
        if (session.snapshot().phase() == SearchSession.Phase.EVALUATE) {
            if (targetVisible(context)) found = true;
            session.advance(input(context, null, SearchSession.LegOutcome.NONE, false, 0));
            if (found) return TaskStatus.RUNNING;
        }
        if (journey == null) {
            ResourceSearchProfile.Strategy strategy = strategy();
            if (strategy != ResourceSearchProfile.Strategy.SURFACE) {
                summary = diagnostic("no observed entrance or safe local route for " + strategy.name().toLowerCase(java.util.Locale.ROOT));
                session.interrupt();
                return TaskStatus.FAILED;
            }
            SearchPlanner.Frontier frontier = planner.choose(new SearchPlanner.Request(
                    context.level().dimension().identifier().toString(), origin, frontiers(), profile,
                    state.knowledge().book(context.companion().ownerUuid()).searchCoverage(),
                    Math.max(0, session.snapshot().limits().ticks() - session.snapshot().spentTicks()),
                    logicalTick(context), heading())).orElse(null);
            if (frontier == null) {
                summary = diagnostic("no unvisited local frontier remains after " + inspected + " viewpoints");
                session.advance(input(context, null, SearchSession.LegOutcome.NONE, false, 0));
                if (distance(current(context), origin) <= 2) return TaskStatus.FAILED;
                returning = true;
                journey = new ResourceJourney(context, context.level().dimension().identifier().toString(), origin,
                        session, state.knowledge().book(context.companion().ownerUuid()).trails(), expedition);
                return TaskStatus.RUNNING;
            }
            session.advance(new SearchSession.Input(logicalTick(context),
                    context.level().dimension().identifier().toString(), position, true, false,
                    SearchSession.LegOutcome.NONE, frontier, 0, 0, 0, frontier.estimatedTravelTicks()));
            activeFrontier = frontier;
            journey = new ResourceJourney(context, context.level().dimension().identifier().toString(),
                    frontier.position(), session, state.knowledge().book(context.companion().ownerUuid()).trails(), expedition);
        }
        return TaskStatus.RUNNING;
    }

    private TaskStatus finishReturn(TaskContext context) {
        journey = null;
        if (found) {
            session.advance(input(context, null, SearchSession.LegOutcome.NONE, false, 0));
            summary = diagnostic("found " + profile.family() + " by direct sight after " + inspected + " viewpoints; returned to origin");
            return TaskStatus.SUCCESS;
        }
        summary = diagnostic("no directly visible " + profile.family() + " after " + inspected + " viewpoints; returned to origin");
        return TaskStatus.FAILED;
    }

    private boolean targetVisible(TaskContext context) {
        return FriendaiServerState.get(context.level().getServer()).resources()
                .confirmedRecent(context.companion(), 16).stream().anyMatch(observation -> targetBlocks.contains(observation.blockId()));
    }

    private List<SearchPlanner.Frontier> frontiers() {
        List<SearchPlanner.Frontier> result = new ArrayList<>();
        int steps = Math.max(1, radius / 16);
        for (int step = 1; step <= steps; step++) {
            int d = step * 16;
            result.add(frontier(origin.x(), origin.y(), origin.z() - d));
            result.add(frontier(origin.x() + d, origin.y(), origin.z()));
            result.add(frontier(origin.x(), origin.y(), origin.z() + d));
            result.add(frontier(origin.x() - d, origin.y(), origin.z()));
        }
        return result;
    }

    private SearchPlanner.Frontier frontier(int x, int y, int z) {
        Pos point = new Pos(x, y, z);
        return new SearchPlanner.Frontier(point, point, strategy(), 1,
                Math.max(1, Math.round(distance(origin, point) * 2)));
    }

    private ResourceSearchProfile.Strategy strategy() {
        return selectedStrategy;
    }

    private BlockMatcher targetMatcher() {
        return new BlockMatcher() {
            @Override public boolean test(net.minecraft.world.level.block.state.BlockState state) {
                return targetBlocks.contains(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
            }
            @Override public String label() { return profile.family(); }
        };
    }

    private SearchPlanner.Heading heading() {
        if (requestedHeading == null || requestedHeading.isBlank()) return SearchPlanner.Heading.NORTH;
        try { return SearchPlanner.Heading.valueOf(requestedHeading.toUpperCase(java.util.Locale.ROOT)); }
        catch (IllegalArgumentException ignored) { return SearchPlanner.Heading.NORTH; }
    }

    private String diagnostic(String reason) {
        SearchSession.Snapshot snapshot = session.snapshot();
        long remainingTicks = Math.max(0, snapshot.limits().ticks() - snapshot.spentTicks());
        double remainingTravel = Math.max(0, snapshot.limits().travelBlocks() - snapshot.spentTravelBlocks());
        int remainingExcavation = Math.max(0, snapshot.limits().excavationBlocks() - snapshot.excavationBlocks());
        String value = "phase=" + snapshot.phase().name().toLowerCase(java.util.Locale.ROOT)
                + "; strategy=" + strategy().name().toLowerCase(java.util.Locale.ROOT)
                + "; time=" + snapshot.spentTicks() + "/" + snapshot.limits().ticks()
                + " ticks (" + remainingTicks + " left); travel="
                + Math.round(snapshot.spentTravelBlocks()) + "/" + Math.round(snapshot.limits().travelBlocks())
                + " blocks (" + Math.round(remainingTravel) + " left); excavation="
                + snapshot.excavationBlocks() + "/" + snapshot.limits().excavationBlocks()
                + " blocks (" + remainingExcavation + " left); " + reason;
        return value.length() <= 600 ? value : value.substring(0, 597) + "...";
    }

    private SearchSession.Input input(TaskContext context, SearchPlanner.Frontier frontier,
            SearchSession.LegOutcome outcome, boolean discovered, long returnEstimate) {
        return new SearchSession.Input(logicalTick(context),
                context.level().dimension().identifier().toString(), current(context), true, discovered,
                outcome, frontier, 0, 0, 0, returnEstimate);
    }

    @Override public void stop(TaskContext context) {
        if (journey != null) journey.stop(context);
        if (miningSearch != null) miningSearch.stop(context);
    }
    @Override public String describe() { return "searching for " + profile.family(); }
    @Override public String resultSummary() { return summary; }

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
