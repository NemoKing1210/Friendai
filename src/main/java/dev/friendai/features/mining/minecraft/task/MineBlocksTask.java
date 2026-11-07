package dev.friendai.features.mining.minecraft.task;

import dev.friendai.common.Components;
import dev.friendai.debug.DebugAction;
import dev.friendai.debug.DebugTarget;
import dev.friendai.debug.WorkCell;
import dev.friendai.debug.WorkKind;
import dev.friendai.debug.WorkSet;
import dev.friendai.debug.WorkStatus;
import dev.friendai.execution.settings.ResourceSearchSettings;
import dev.friendai.execution.tool.Caller;
import dev.friendai.features.mining.MiningBounds;
import dev.friendai.features.mining.minecraft.mining.BlockFilter;
import dev.friendai.features.mining.minecraft.mining.CatchPlatform;
import dev.friendai.features.mining.minecraft.mining.DropGuard;
import dev.friendai.features.mining.minecraft.mining.DropRoom;
import dev.friendai.features.mining.minecraft.mining.MiningSession;
import dev.friendai.features.mining.minecraft.mining.TemporaryBlockCleanup;
import dev.friendai.features.mining.minecraft.mining.TreeFinder;
import dev.friendai.features.mining.tree.TreeCell;
import dev.friendai.features.mining.tree.TrunkExpansion;
import dev.friendai.features.places.PlaceAutoRegistration;
import dev.friendai.features.places.PlaceCandidate;
import dev.friendai.foundation.spatial.Node;
import dev.friendai.foundation.spatial.Pos;
import dev.friendai.task.BlockSearchTaskFactory;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.inventory.DropCollector;
import dev.friendai.task.inventory.ItemMatcher;
import dev.friendai.world.block.BlockBreaker;
import dev.friendai.world.block.BlockMatcher;
import dev.friendai.world.block.MiningRefusal;
import dev.friendai.world.block.MiningToolView;
import dev.friendai.world.block.ScaffoldCleanupTask;
import dev.friendai.world.placement.PlacementLedger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Finds, walks to, breaks N blocks of a kind and sweeps up what they drop. */
public final class MineBlocksTask implements Task {
    private static final Logger LOGGER = LoggerFactory.getLogger("friendai/task");
    private final BlockFilter matcher;
    private final int count;
    private final int radius;
    private final Set<BlockPos> blacklist = new HashSet<>();

    /** How many of the nearest matching blocks one walk may choose from. */
    private static final int CANDIDATES = 8;
    /** How far from the last broken block the sweep after a finished tree or job looks for its drops. */
    private static final int SWEEP_RADIUS = 8;
    /** How close a drop must be to be taken without walking, while the job goes on. */
    private static final double FEET_RADIUS = 1.5;
    /** Allow the shared sight scheduler to inspect newly exposed faces before declaring the vein exhausted. */
    private static final int OBSERVATION_WAIT_TICKS = 400;
    /** How many cells of the planned passage the overlay draws around a mining session. */
    private static final int PASSAGE_DEBUG_CELLS = 64;

    private BlockBreaker breaker;
    /** Whether the job already let itself break the block it stands on, once per remaining target. */
    private boolean supportRetry;

    private MiningSession miningSession;
    private boolean miningSessionActive;
    private DropCollector sweep;
    /** Blocks placed to pillar or bridge to a block; removed before the task reports a result. */
    private final PlacementLedger placements = new PlacementLedger();

    private TemporaryBlockCleanup cleanup;
    private TaskStatus pendingStatus;
    /** The catch platform under the block being broken now, and the target it was planned for. */
    private CatchPlatform platform;

    private BlockPos platformTarget;
    private int mined;
    /** The last block mined: the rest of its tree is worked on before any other block is considered. */
    private BlockPos lastBroken;

    private BlockPos pendingObservation;
    private int observationWaitTicks;
    private String summary = "";
    /** The last typed refusal, so a job that stops says what stopped it. */
    private MiningRefusal lastRefusal;
    /** The box this job actually broke; the place it may be remembered as covers exactly it. */
    private BlockPos minMined;

    private BlockPos maxMined;
    /** The dimension the job ran in, captured at start because a place candidate is built without a context. */
    private String dimension = "";
    /** {@code places.autoMineMinBlocks}, captured at start: a small dig is not a mine. */
    private int autoMinBlocks = Integer.MAX_VALUE;

    /** Where to search from; null means around the companion. */
    private final BlockPos origin;
    /** Whether {@link #origin} came from a remembered place, which the miss sentence reaches with go_to_place. */
    private final boolean originIsPlace;
    /** Whether to walk around picking the drops up afterwards; drops at the feet are always taken. */
    private final boolean collect;
    /** When true, candidates come only from the placement ledger; unplaced siblings are never mined. */
    private final boolean placedOnly;
    /** When true, a block that needs a harvest tool is skipped unless the companion carries one. */
    private final boolean requireTool;
    /** The item goal, or null; {@link #untilCount} is then zero. */
    private final ItemMatcher untilMatcher;

    private final int untilCount;
    private int untilTaken;
    private boolean sweepForUntil;
    private boolean finalSweep;
    private boolean toolSkipped;
    /**
     * The one search this job may run when a scan finds nothing; never searches when its factory is unavailable, when
     * the call named its own origin, or when the job is placed-only. It also owns the job's principal, so the tick
     * site cannot substitute a different authority.
     */
    private final Exploration exploration;
    /**
     * After a successful search, the configured local reach around the companion. The search can end where the target
     * was seen, beyond this job's own radius, so the one re-scan and the walk that follows use this reach instead.
     */
    private int discoveryRadius;

    public MineBlocksTask(BlockMatcher matcher, int count, int radius) {
        this(matcher, count, radius, null, true, false);
    }

    public MineBlocksTask(BlockMatcher matcher, int count, int radius, BlockPos origin, boolean collect) {
        this(matcher, count, radius, origin, collect, false);
    }

    public MineBlocksTask(
            BlockMatcher matcher, int count, int radius, BlockPos origin, boolean collect, boolean placedOnly) {
        this(new BlockFilter(matcher, List.of()), count, radius, origin, collect, placedOnly, false, null, 0);
    }

    public MineBlocksTask(
            BlockFilter matcher,
            int count,
            int radius,
            BlockPos origin,
            boolean collect,
            boolean placedOnly,
            boolean requireTool,
            ItemMatcher untilMatcher,
            int untilCount) {
        this(
                matcher,
                count,
                radius,
                origin,
                collect,
                placedOnly,
                requireTool,
                untilMatcher,
                untilCount,
                BlockSearchTaskFactory.NONE,
                true,
                null);
    }

    /**
     * @param searchFactory the port one bounded search for an unseen target goes through; {@link
     *     BlockSearchTaskFactory#NONE} disables it
     * @param explore whether a scan that finds nothing may run that search once; a placed-only job never does
     */
    public MineBlocksTask(
            BlockFilter matcher,
            int count,
            int radius,
            BlockPos origin,
            boolean collect,
            boolean placedOnly,
            boolean requireTool,
            ItemMatcher untilMatcher,
            int untilCount,
            BlockSearchTaskFactory searchFactory,
            boolean explore) {
        this(
                matcher,
                count,
                radius,
                origin,
                collect,
                placedOnly,
                requireTool,
                untilMatcher,
                untilCount,
                searchFactory,
                explore,
                null);
    }

    /**
     * @param searchCaller the principal the job runs for, handed to a delegated search unchanged; null for a job that
     *     cannot search (one whose factory is {@link BlockSearchTaskFactory#NONE})
     */
    public MineBlocksTask(
            BlockFilter matcher,
            int count,
            int radius,
            BlockPos origin,
            boolean collect,
            boolean placedOnly,
            boolean requireTool,
            ItemMatcher untilMatcher,
            int untilCount,
            BlockSearchTaskFactory searchFactory,
            boolean explore,
            Caller searchCaller) {
        this(
                matcher,
                count,
                radius,
                origin,
                collect,
                placedOnly,
                requireTool,
                untilMatcher,
                untilCount,
                searchFactory,
                explore,
                searchCaller,
                false);
    }

    /**
     * @param originIsPlace whether the explicit origin came from a remembered place, which {@code go_to_place} can
     *     navigate to by name; a coordinates or speaker origin is reached with {@code go_to}
     */
    public MineBlocksTask(
            BlockFilter matcher,
            int count,
            int radius,
            BlockPos origin,
            boolean collect,
            boolean placedOnly,
            boolean requireTool,
            ItemMatcher untilMatcher,
            int untilCount,
            BlockSearchTaskFactory searchFactory,
            boolean explore,
            Caller searchCaller,
            boolean originIsPlace) {
        this.matcher = matcher;
        this.count = count;
        this.radius = radius;
        this.origin = origin;
        this.collect = collect;
        this.placedOnly = placedOnly;
        this.requireTool = requireTool;
        this.untilMatcher = untilMatcher;
        this.untilCount = untilCount;
        this.originIsPlace = originIsPlace;
        this.exploration = new Exploration(searchFactory, maySearch(explore, placedOnly, origin), searchCaller);
    }

    /**
     * Whether this job may delegate one bounded search when its own scan found nothing. A call that named its own
     * origin ({@code near}, a remembered place or coordinates) never searches: the search starts from the companion
     * and could walk away from the place the caller named, so the job reports what it could not find around that
     * origin instead. A placed-only job never searches either, and {@code explore:false} always wins.
     */
    static boolean maySearch(boolean explore, boolean placedOnly, BlockPos origin) {
        return explore && !placedOnly && origin == null;
    }

    /** The principal this job's delegated search runs with; package-private so a test can pin it to the call. */
    Caller searchCaller() {
        return exploration.caller();
    }

    @Override
    public String type() {
        return "mine_blocks";
    }

    /** The blocks this job was sent for are not path material: a climb must find its own. */
    @Override
    public java.util.Map<String, Integer> materialNeeds(TaskContext context) {
        int remaining = count - mined;
        String label = matcher.label();
        if (remaining <= 0 || label.isBlank() || label.startsWith("#")) {
            return java.util.Map.of();
        }
        return java.util.Map.of(label, remaining);
    }

    @Override
    public List<DebugTarget> debugTargets() {
        if (miningSessionActive) {
            List<DebugTarget> cells = new ArrayList<>();
            BlockPos target = miningSession == null ? null : miningSession.target();
            if (target != null) {
                cells.add(target(target));
            }
            if (miningSession != null) {
                // The corridor of the whole planned passage, not only the step under execution: a half stair or a
                // dead end is visible to the owner before the companion walks into it.
                for (Pos pos : miningSession.plannedBreaks()) {
                    if (cells.size() >= PASSAGE_DEBUG_CELLS) {
                        break;
                    }
                    cells.add(new DebugTarget(pos.x(), pos.y(), pos.z(), DebugAction.BREAK, "Path obstacle"));
                }
                cells.addAll(catchTargets(miningSession.catchCells()));
            }
            return cells;
        }
        if (platform != null) {
            List<DebugTarget> targets = new ArrayList<>();
            if (breaker != null && breaker.target() != null) {
                targets.add(target(breaker.target()));
            }
            targets.addAll(catchTargets(platform.cells()));
            return targets;
        }
        if (breaker == null) {
            return List.of();
        }
        BlockPos selected = breaker.target();
        if (selected != null) {
            return List.of(target(selected));
        }
        return breaker.candidates().stream().map(this::target).toList();
    }

    @Override
    public List<WorkSet> debugWorkSets() {
        List<BlockPos> positions = new ArrayList<>();
        if (miningSessionActive && miningSession != null) {
            positions.addAll(miningSession.targets());
            if (miningSession.target() != null && !positions.contains(miningSession.target()))
                positions.add(0, miningSession.target());
        } else if (breaker != null) {
            if (breaker.target() != null) positions.add(breaker.target());
            else positions.addAll(breaker.candidates());
        }
        if (positions.isEmpty()) return List.of();
        BlockPos selected = miningSessionActive && miningSession != null
                ? miningSession.target()
                : breaker == null ? positions.get(0) : breaker.target() == null ? positions.get(0) : breaker.target();
        List<WorkCell> cells = positions.stream()
                .distinct()
                .map(pos -> new WorkCell(
                        pos.getX(),
                        pos.getY(),
                        pos.getZ(),
                        DebugAction.BREAK,
                        selected != null && selected.equals(pos) ? WorkStatus.NEXT : WorkStatus.PENDING,
                        "Mine " + matcher.label()))
                .toList();
        int nextCount = (int)
                cells.stream().filter(cell -> cell.status() == WorkStatus.NEXT).count();
        return List.of(new WorkSet(
                matcher.label(),
                WorkKind.BLOCKS,
                cells.size(),
                0,
                nextCount,
                cells.size() - nextCount,
                0,
                false,
                cells));
    }

    private static List<DebugTarget> catchTargets(List<BlockPos> cells) {
        return cells.stream()
                .map(cell -> new DebugTarget(cell.getX(), cell.getY(), cell.getZ(), DebugAction.PLACE, "catch"))
                .toList();
    }

    private DebugTarget target(BlockPos pos) {
        MiningRefusal refusal = breaker == null ? null : breaker.refusal();
        return new DebugTarget(
                pos.getX(),
                pos.getY(),
                pos.getZ(),
                DebugAction.BREAK,
                refusal == null ? matcher.label() : refusal.describe());
    }

    @Override
    public void start(TaskContext c) {
        dimension = c.level().dimension().identifier().toString();
        autoMinBlocks = c.autoMineMinBlocks();
    }

    @Override
    public TaskStatus tick(TaskContext c) {
        if (cleanup != null) {
            TemporaryBlockCleanup.State state = cleanup.tick(c);
            if (state == TemporaryBlockCleanup.State.WORKING) {
                return TaskStatus.RUNNING;
            }
            TemporaryBlockCleanup finished = cleanup;
            cleanup = null;
            if (state == TemporaryBlockCleanup.State.FAILED) {
                summary += "; left " + finished.left() + " temporary blocks (" + finished.failure() + ")";
            }
            if (finished.kept() > 0) {
                summary += "; kept " + finished.kept() + " temporary block(s) under the companion";
            }
            placements.clear();
            TaskStatus status = pendingStatus;
            pendingStatus = null;
            return status;
        }
        TaskStatus status = tickTask(c);
        if (status != TaskStatus.RUNNING && !placements.isEmpty()) {
            cleanup = new TemporaryBlockCleanup(placements);
            pendingStatus = status;
            return TaskStatus.RUNNING;
        }
        return status;
    }

    private TaskStatus tickTask(TaskContext c) {
        DropCollector.pickupAtFeet(c, FEET_RADIUS, null);
        TaskStatus guarded = tickCatchPlatform(c);
        if (guarded != null) {
            return guarded;
        }
        if (sweep != null) {
            if (sweep.tick(c) != DropCollector.State.DONE) {
                return TaskStatus.RUNNING;
            }
            if (sweepForUntil) {
                untilTaken += matchingTaken(sweep.collectedStacks());
            }
            boolean wasFinal = finalSweep;
            sweep = null;
            sweepForUntil = false;
            finalSweep = false;
            if (untilReached()) {
                summary = "took " + untilTaken + " " + untilMatcher.label() + " after mining " + mined + " "
                        + matcher.label();
                return TaskStatus.SUCCESS;
            }
            if (wasFinal && untilMatcher != null) {
                summary = "mined " + mined + " " + matcher.label() + ", took " + untilTaken + " of " + untilCount + " "
                        + untilMatcher.label();
                return TaskStatus.FAILED;
            }
            return TaskStatus.RUNNING;
        }
        if (miningSessionActive) {
            // The session itself refuses a target when the inventory has no room, once per target rather than
            // once per tick.
            return tickMiningSession(c);
        }
        if (breaker == null) {
            return search(c);
        }
        return switch (breaker.tick(c)) {
            case WORKING -> TaskStatus.RUNNING;
            case BROKEN -> {
                lastBroken = breaker.target();
                pendingObservation = lastBroken;
                observationWaitTicks = 0;
                breaker = null;
                supportRetry = false;
                mined++;
                reportMined(c);
                lastRefusal = null;
                rememberMined(lastBroken);
                c.placedService().forget(c.companion(), lastBroken);
                if (untilMatcher != null && !finalSweep) {
                    // Stop and take what this pass dropped, so until_item counts items, not blocks.
                    sweep = new DropCollector(Vec3.atCenterOf(lastBroken), SWEEP_RADIUS, untilMatcher);
                    sweepForUntil = true;
                }
                yield TaskStatus.RUNNING;
            }
            case GONE -> {
                breaker = null;
                yield TaskStatus.RUNNING;
            }
            case UNREACHABLE -> {
                // None of the eight can be reached: blacklist them all, or the next search finds them again. The
                // trunk this block belonged to is given up with them, so the search may look elsewhere.
                blacklist.addAll(breaker.candidates());
                lastBroken = null;
                breaker = null;
                yield TaskStatus.RUNNING;
            }
            case PROTECTED, OUT_OF_SIGHT -> {
                // The last block of a column can be the one the companion stands on. Its drop has somewhere safe
                // to land, so step off it by breaking it instead of stopping the job one block short.
                if (breaker.refusal() instanceof MiningRefusal.OwnSupport own && own.safeDrop() && !supportRetry) {
                    supportRetry = true;
                    breaker.stop(c);
                    breaker = breaker.allowingSupport();
                    yield TaskStatus.RUNNING;
                }
                // Only the block the companion actually stood by: a sibling may still be reachable.
                if (breaker.refusal() != null) {
                    lastRefusal = breaker.refusal();
                }
                if (breaker.target() != null) {
                    blacklist.add(breaker.target());
                }
                breaker = null;
                yield TaskStatus.RUNNING;
            }
        };
    }

    /**
     * Holds the breaker until the drop of the block it reached has somewhere to land, and refuses a valuable block
     * whose match cannot be built. Returns null when the ordinary tick should continue.
     */
    private TaskStatus tickCatchPlatform(TaskContext c) {
        if (platform != null) {
            CatchPlatform.State state = platform.tick(c);
            if (state == CatchPlatform.State.WORKING) {
                return TaskStatus.RUNNING;
            }
            CatchPlatform finished = platform;
            platform = null;
            if (state == CatchPlatform.State.REFUSED) {
                lastRefusal = new MiningRefusal.DropUnprotected(
                        platformTarget,
                        null,
                        dev.friendai.foundation.mining.DropRisk.Reason.PROTECTED_CELL,
                        finished.failure());
                blacklist.add(platformTarget);
                if (breaker != null) {
                    breaker.stop(c);
                    breaker = null;
                }
                return TaskStatus.RUNNING;
            }
        }
        if (breaker != null && breaker.target() != null && !breaker.target().equals(platformTarget)) {
            platformTarget = breaker.target();
            DropGuard.Plan plan = DropGuard.plan(c, platformTarget, placements);
            if (plan.refusal() != null) {
                lastRefusal = plan.refusal();
                blacklist.add(platformTarget);
                breaker.stop(c);
                breaker = null;
                return TaskStatus.RUNNING;
            }
            platform = plan.platform();
        }
        return null;
    }

    /** One bounded evidence record per broken block: counts and the target label, no coordinates. */
    private void reportMined(TaskContext c) {
        java.util.Map<String, String> facts = new java.util.LinkedHashMap<>();
        facts.put("mined", Integer.toString(mined));
        facts.put("count", Integer.toString(count));
        facts.put("target", matcher.label());
        c.decision(dev.friendai.foundation.diagnostics.LogEvents.INVENTORY_DELTA, "block_mined", facts);
    }

    /** Captures why mining stopped before the summary is cleared; the reason token is stable, not prose. */
    private void reportMiningFailure(TaskContext c, String reason) {
        java.util.Map<String, String> facts = new java.util.LinkedHashMap<>();
        facts.put("mined", Integer.toString(mined));
        facts.put("count", Integer.toString(count));
        facts.put("target", matcher.label());
        c.decision(dev.friendai.foundation.diagnostics.LogEvents.INVENTORY_DELTA, reason, facts);
    }

    private TaskStatus tickMiningSession(TaskContext c) {
        MiningSession.State state = miningSession.tick(c);
        return switch (state) {
            case WORKING -> TaskStatus.RUNNING;
            case EXTRACTED -> {
                lastBroken = miningSession.target();
                pendingObservation = lastBroken;
                observationWaitTicks = 0;
                mined++;
                reportMined(c);
                rememberMined(lastBroken);
                c.placedService().forget(c.companion(), lastBroken);
                miningSessionActive = false;
                yield TaskStatus.RUNNING;
            }
            case EXHAUSTED -> {
                miningSessionActive = false;
                reportMiningFailure(c, "search_exhausted");
                summary = "mined " + mined + " of " + count + ", mining search limit reached";
                yield TaskStatus.FAILED;
            }
            case FAILED -> {
                miningSessionActive = false;
                reportMiningFailure(c, "mining_failed");
                String reason = miningSession.failure() == null
                        ? "the resource became unreachable"
                        : failureText(miningSession.failure());
                summary = "mined " + mined + " of " + count + ", " + reason;
                yield TaskStatus.FAILED;
            }
        };
    }

    private TaskStatus search(TaskContext c) {
        toolSkipped = false;
        if (mined >= count) {
            if (startSweep()) {
                return TaskStatus.RUNNING;
            }
            if (untilMatcher != null && untilTaken < untilCount) {
                summary = "mined " + mined + " " + matcher.label() + ", took " + untilTaken + " of " + untilCount + " "
                        + untilMatcher.label();
                return TaskStatus.FAILED;
            }
            summary = "mined " + mined + " " + matcher.label();
            return TaskStatus.SUCCESS;
        }
        // A successful search leaves the companion where the target was seen, which can be farther than this job's
        // own radius; the one re-scan and the walk that follows then use the search's configured local reach instead.
        boolean afterSearch = discoveryRadius > 0;
        int reach = afterSearch ? discoveryRadius : radius;
        BlockPos around = afterSearch ? null : origin;
        List<BlockPos> candidates;
        if (placedOnly) {
            candidates = new ArrayList<>(c.placedService()
                    .candidates(c.companion(), matcher, origin, radius, evidenceLimit(CANDIDATES * 4)));
            candidates.removeIf(this::excluded);
        } else {
            candidates = treeCandidates(c);
            if (candidates.isEmpty()) {
                if (startSweep()) {
                    // The tree is finished: pick its drops up before going anywhere else.
                    return TaskStatus.RUNNING;
                }
                var evidence = c.resources();
                int evidenceRadius = around == null
                        ? reach
                        : Math.min(128, c.companion().blockPosition().distManhattan(around) + reach);
                candidates = new ArrayList<>(evidence.knownOrVisible(
                        c.companion(), matcher, evidenceRadius, evidenceLimit(around == null ? CANDIDATES * 4 : 64)));
                candidates.removeIf(this::excluded);
                if (around != null) {
                    double radiusSquared = (double) reach * reach;
                    double originX = around.getX() + 0.5, originY = around.getY() + 0.5, originZ = around.getZ() + 0.5;
                    candidates.removeIf(pos -> pos.distToCenterSqr(originX, originY, originZ) > radiusSquared);
                    candidates.sort(java.util.Comparator.comparingDouble(
                            pos -> pos.distToCenterSqr(originX, originY, originZ)));
                } else if (candidates.size() > CANDIDATES) {
                    candidates = new ArrayList<>(candidates.subList(0, CANDIDATES));
                }
            }
        }
        // Historical evidence can outlive a registry block's current state. Keep the old scanner's
        // safety property: an unbreakable match is not an executable mining target.
        boolean creative = c.companion().creativeMode();
        candidates.removeIf(pos -> {
            BlockState state = c.level().getBlockState(pos);
            return !matcher.test(state) || (!creative && state.getDestroySpeed(c.level(), pos) < 0);
        });
        // Ask the same question the breaker will ask, before spending the walk: a protected or unbreakable
        // candidate is no target. Own support stays: the session can move off it or break it safely.
        List<BlockPos> legal = new ArrayList<>(candidates.size());
        for (BlockPos pos : candidates) {
            MiningRefusal refusal = MiningRefusal.check(c, pos, false);
            if (refusal == null || refusal instanceof MiningRefusal.OwnSupport) {
                legal.add(pos);
            } else {
                lastRefusal = refusal;
            }
        }
        candidates = legal;
        if (requireTool) {
            List<BlockPos> harvestable = new ArrayList<>(candidates.size());
            for (BlockPos pos : candidates) {
                BlockState state = c.level().getBlockState(pos);
                if (!state.requiresCorrectToolForDrops()
                        || MiningToolView.currentToolFor(c.companion(), state).isPresent()) {
                    harvestable.add(pos);
                } else {
                    toolSkipped = true;
                }
            }
            candidates = harvestable;
        }
        if (around != null) {
            double originX = around.getX() + 0.5, originY = around.getY() + 0.5, originZ = around.getZ() + 0.5;
            candidates.sort(
                    java.util.Comparator.comparingDouble(pos -> pos.distToCenterSqr(originX, originY, originZ)));
            if (candidates.size() > 1) {
                // BlockBreaker walks from the companion, so choose the nearest legal evidence to the requested
                // origin after the protection/tool filters, not before them.
                candidates = new ArrayList<>(candidates.subList(0, 1));
            }
        } else if (candidates.size() > CANDIDATES) {
            candidates = new ArrayList<>(candidates.subList(0, CANDIDATES));
        }
        if (candidates.isEmpty()) {
            if (pendingObservation != null && observationWaitTicks < OBSERVATION_WAIT_TICKS) {
                if (observationWaitTicks++ == 0) {
                    var observer = c.observations().observer(c.companion());
                    for (net.minecraft.core.Direction side : net.minecraft.core.Direction.values()) {
                        observer.inspect(pendingObservation.relative(side));
                    }
                }
                return TaskStatus.RUNNING;
            }
            pendingObservation = null;
            String searchReason = "";
            if (!toolSkipped) {
                // The budget is the live configured local search budget, not a number of its own: the server owner
                // already tunes one bounded local search, and this job delegates to it. The origin is implicitly the
                // companion: {@link #maySearch} never lets an explicit origin get here.
                Exploration.Step step =
                        exploration.missing(c, matcher.includeLabel(), radius, c.resourceSearchSettings());
                if (step == Exploration.Step.RUNNING) {
                    return TaskStatus.RUNNING;
                }
                if (step == Exploration.Step.RESCAN) {
                    // The search found something new: scan once more with the evidence it gathered, using the
                    // search's own configured reach around where the companion now stands.
                    discoveryRadius =
                            Math.max(radius, c.resourceSearchSettings().localRadius());
                    return search(c);
                }
                searchReason = exploration.failureSummary();
            }
            // Say which it is: nothing around, blocks that were found and could not be reached, or what the
            // delegated search itself reported (a missing pickaxe, an unavailable owner, its spent budget).
            boolean hidden = !blacklist.isEmpty();
            if (!searchReason.isBlank()) {
                // Keep what the job already achieved: the search's own reason replaces the scan's sentence, never
                // the count of blocks the caller asked for.
                summary = minedThenReason(mined, count, searchReason);
            } else if (mined == 0) {
                if (origin != null && !placedOnly) {
                    summary = explicitOriginMiss(matcher.label(), radius, origin, hidden, originIsPlace);
                } else {
                    summary = placedOnly
                            ? "no " + matcher.label() + " you placed nearby"
                            : hidden
                                    ? "could not reach any " + matcher.label() + " nearby"
                                    : "no " + matcher.label()
                                            + " observed or remembered nearby; use search_resource to search for it, "
                                            + "or look_around for a visible exposure";
                }
            } else {
                summary = minedThenReason(
                        mined,
                        count,
                        "no more " + matcher.label()
                                + (placedOnly
                                        ? " you placed nearby"
                                        : hidden ? " could be reached" : " observed or remembered nearby"));
            }
            if (toolSkipped && lastRefusal == null) {
                summary = "no suitable tool to collect " + matcher.label() + " nearby";
            }
            if (lastRefusal != null) {
                summary += "; " + lastRefusal.describe() + "; " + lastRefusal.remedy();
            }
            return TaskStatus.FAILED;
        }
        pendingObservation = null;
        if (creative) {
            // In creative, mine candidates directly: the survival excavation planner rejects unbreakable states
            // and may plan corridor breaks that have no corresponding creative-player semantics.
            breaker = new BlockBreaker(candidates, matcher::test, true, false, true, false, placements);
            return TaskStatus.RUNNING;
        }
        boolean logs =
                candidates.stream().allMatch(pos -> c.level().getBlockState(pos).is(BlockTags.LOGS));
        if (!logs) {
            if (placedOnly) {
                // A corridor planner may break blocks outside the ledger. A placed-only job uses the plain
                // breaker instead: no unplaced block is ever broken to reach a recorded one.
                if (!DropRoom.hasRoom(c, candidates.get(0))) {
                    summary = "mined " + mined + " of " + count + ", inventory full before the next drop";
                    c.owner()
                            .ifPresent(player -> player.sendSystemMessage(
                                    Components.of(dev.friendai.foundation.text.UserMessage.of(
                                            "friendai.mining.inventory_full")),
                                    true));
                    return TaskStatus.FAILED;
                }
                breaker = new BlockBreaker(candidates, matcher::test, true, true, false, false, placements);
                return TaskStatus.RUNNING;
            }
            if (miningSession == null) {
                BlockPos start = around != null ? around : c.companion().blockPosition();
                miningSession = new MiningSession(
                        c.companion(),
                        new MiningBounds(new Node(start.getX(), start.getY(), start.getZ()), reach),
                        128,
                        6_000,
                        placements);
            }
            miningSession.setTargets(candidates, matcher::test);
            miningSessionActive = true;
            return TaskStatus.RUNNING;
        }
        // Tree felling keeps its existing placement route and connected-log behavior.
        if (!DropRoom.hasRoom(c, candidates.get(0))) {
            summary = "mined " + mined + " of " + count + ", inventory full before the next drop";
            c.owner()
                    .ifPresent(player -> player.sendSystemMessage(
                            Components.of(
                                    dev.friendai.foundation.text.UserMessage.of("friendai.mining.inventory_full")),
                            true));
            return TaskStatus.FAILED;
        }
        breaker = new BlockBreaker(candidates, matcher::test, true, true, !placedOnly, false, placements);
        return TaskStatus.RUNNING;
    }

    /**
     * The miss sentence for a job that was told to stay around a named origin. It names that origin and never points
     * at {@code search_resource}, which has no origin of its own and would search from the companion — the very
     * wandering the explicit-origin rule removes.
     *
     * <p>The advice names the tool that can actually reach the origin: {@code go_to_place} takes a remembered place's
     * name, which only a {@code place:} origin has, and {@code go_to} takes the coordinates of a point or the owner.
     */
    static String explicitOriginMiss(String label, int radius, BlockPos origin, boolean hidden, boolean originIsPlace) {
        String where = origin.getX() + " " + origin.getY() + " " + origin.getZ();
        String head = hidden
                ? "could not reach any " + label + " within " + radius + " blocks of " + where
                : "no " + label + " observed or remembered within " + radius + " blocks of " + where;
        String goThere = originIsPlace ? "go_to_place" : "go_to";
        return head + "; call mine_blocks without near to search from the companion, or go there first (" + goThere
                + ")";
    }

    /**
     * What a job that mined some blocks says when a later step failed: the achieved count stays, then the reason
     * that stopped it. A job that mined nothing says only the reason.
     */
    static String minedThenReason(int mined, int count, String reason) {
        return mined > 0 ? "mined " + mined + " of " + count + ", " + reason : reason;
    }

    /** The player sees a translated message; the model needs an English sentence it can act on. */
    private static String failureText(dev.friendai.foundation.text.UserMessage failure) {
        return switch (failure.key()) {
            case "friendai.mining.no_tool" ->
                "no suitable tool to collect the block; get the right tool first (obtain_item plans the chain)";
            case "friendai.mining.unsafe_support" -> "the block supports the companion or its route";
            case "friendai.mining.server_protected" -> "the server protects it (spawn protection or a world rule)";
            case "friendai.mining.place_protected" -> "it is inside a place the owner protects";
            case "friendai.mining.own_support_unsafe" -> "it is under the companion's feet and the drop is unsafe";
            case "friendai.mining.not_breakable" -> "it cannot be broken";
            case "friendai.mining.unsafe_passage" -> "the passage is unsafe to excavate";
            case "friendai.mining.unreachable" -> "the resource cannot be reached safely";
            case "friendai.mining.search_limit" -> "the mining search limit was reached";
            case "friendai.mining.inventory_full" -> "inventory full before the next drop";
            default -> failure.key();
        };
    }

    /**
     * Starts a round on the drops around the last broken block; false when there is no block to sweep from or the
     * drops are not wanted.
     */
    private boolean startSweep() {
        if (lastBroken == null) {
            return false;
        }
        if (!collect) {
            lastBroken = null;
            return false;
        }
        sweep = new DropCollector(Vec3.atCenterOf(lastBroken), SWEEP_RADIUS);
        sweepForUntil = untilMatcher != null;
        finalSweep = true;
        lastBroken = null;
        return true;
    }

    private int matchingTaken(List<ItemStack> stacks) {
        if (untilMatcher == null) {
            return 0;
        }
        int total = 0;
        for (ItemStack stack : stacks) {
            if (untilMatcher.test(stack)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private boolean untilReached() {
        return untilMatcher != null && untilTaken >= untilCount;
    }

    /**
     * The blocks of the same tree or vein as the last mined one, so one tree is felled before the next is started.
     * Empty when there is no last block or its surroundings hold nothing of the kind.
     */
    private List<BlockPos> treeCandidates(TaskContext c) {
        if (lastBroken == null) {
            return new ArrayList<>();
        }
        TrunkExpansion.Cells cells = new TrunkExpansion.Cells() {
            @Override
            public boolean isLog(int x, int y, int z) {
                return matcher.test(c.level().getBlockState(new BlockPos(x, y, z)));
            }

            @Override
            public boolean isLoaded(int x, int y, int z) {
                return c.level().hasChunkAt(new BlockPos(x, y, z));
            }
        };
        List<TreeCell> seed = List.of(new TreeCell(lastBroken.getX(), lastBroken.getY(), lastBroken.getZ()));
        List<BlockPos> result = new ArrayList<>();
        double limit = (double) radius * radius;
        for (TreeCell cell : TrunkExpansion.expand(seed, cells, TreeFinder.MAX_SCANNED_LOGS)) {
            BlockPos pos = new BlockPos(cell.x(), cell.y(), cell.z());
            if (matcher.test(c.level().getBlockState(pos)) && !excluded(pos) && pos.distSqr(lastBroken) <= limit) {
                result.add(pos);
            }
        }
        result.sort(java.util.Comparator.comparingDouble(pos -> pos.distSqr(lastBroken)));
        return result.size() <= CANDIDATES ? result : new ArrayList<>(result.subList(0, CANDIDATES));
    }

    private boolean excluded(BlockPos pos) {
        return blacklist.contains(pos) || miningSession != null && miningSession.protects(pos);
    }

    private int evidenceLimit(int wanted) {
        int supports = miningSession == null ? 0 : miningSession.protectedSupportCount();
        // A long job must not turn its growing blacklist into a growing evidence fetch on every rescan.
        return Math.min(512, wanted + blacklist.size() + supports);
    }

    @Override
    public void stop(TaskContext c) {
        exploration.stop(c);
        if (platform != null) {
            platform.stop();
            platform = null;
        }
        if (cleanup != null) {
            cleanup.stop(c);
            cleanup = null;
        }
        if (sweep != null) {
            sweep.stop(c);
            sweep = null;
        }
        if (breaker != null) {
            breaker.stop(c);
        } else {
            c.companion().getNavigation().stop();
        }
        if (miningSession != null) {
            miningSession.stop(c);
            miningSessionActive = false;
        }
    }

    /** A cancelled job takes its catch blocks and route pillars down like a cancelled build takes its scaffold. */
    @Override
    public Task cleanupAfterCancel(TaskContext c) {
        return placements.isEmpty() ? null : new ScaffoldCleanupTask(placements.positions());
    }

    @Override
    public long progressStamp() {
        return mined;
    }

    /**
     * The job's two silent waits are bounded work, not a stall: the one delegated search runs under its own configured
     * budget, and the observation pause gives newly exposed faces a chance to be seen. Reporting them here keeps the
     * runner's generic stall window from cutting either one short.
     */
    @Override
    public int waitingTicks() {
        if (exploration.searching()) return exploration.waitingTicks();
        if (pendingObservation != null) return observationWaitTicks;
        return -1;
    }

    @Override
    public int waitingLimitTicks() {
        if (exploration.searching()) return exploration.waitingLimitTicks();
        if (pendingObservation != null) return OBSERVATION_WAIT_TICKS;
        return 0;
    }

    @Override
    public String describe() {
        return "mining " + count + " " + matcher.label()
                + (origin != null ? " around " + origin.getX() + " " + origin.getY() + " " + origin.getZ() : "")
                + (pendingObservation != null && observationWaitTicks > 0 ? " (checking exposed blocks)" : "");
    }

    /** One line for the debug overlay and a failing test's tick trace: the phase, the target and what refused it. */
    @Override
    public String debugStatus() {
        StringBuilder text = new StringBuilder(miningSessionActive ? "mine " : "search ");
        text.append(matcher.label()).append(' ').append(mined).append('/').append(count);
        if (miningSessionActive && miningSession != null) {
            text.append(" phase=").append(miningSession.phase());
            String reason = miningSession.debugReason();
            if (!reason.isEmpty()) text.append(' ').append(reason);
            BlockPos target = miningSession.target();
            if (target != null) {
                text.append(" target=")
                        .append(target.getX())
                        .append(',')
                        .append(target.getY())
                        .append(',')
                        .append(target.getZ());
            }
            var failure = miningSession.failure();
            if (failure != null) text.append(" failure=").append(failure.key());
        }
        if (pendingObservation != null) text.append(" waiting=").append(observationWaitTicks);
        if (exploration.searching()) text.append(" searching");
        if (lastRefusal != null) text.append(" refused=").append(lastRefusal.describe());
        return text.toString();
    }

    @Override
    public String resultSummary() {
        return summary;
    }

    /** The first block broken keeps the place anchored where the ore or stone was, not where the search started. */
    private void rememberMined(BlockPos minedBlock) {
        if (minedBlock == null) {
            return;
        }
        minMined = minMined == null
                ? minedBlock
                : new BlockPos(
                        Math.min(minMined.getX(), minedBlock.getX()),
                        Math.min(minMined.getY(), minedBlock.getY()),
                        Math.min(minMined.getZ(), minedBlock.getZ()));
        maxMined = maxMined == null
                ? minedBlock
                : new BlockPos(
                        Math.max(maxMined.getX(), minedBlock.getX()),
                        Math.max(maxMined.getY(), minedBlock.getY()),
                        Math.max(maxMined.getZ(), minedBlock.getZ()));
    }

    /**
     * The dug-out site, only once the job was big enough to be a mine. The place covers the box actually dug, not the
     * radius the job searched in: a wide search is not a wide mine.
     */
    @Override
    public PlaceCandidate placeCandidate() {
        if (minMined == null || maxMined == null) {
            return null;
        }
        return PlaceAutoRegistration.mineCandidate(
                        mined,
                        autoMinBlocks,
                        dimension,
                        new Pos(minMined.getX(), minMined.getY(), minMined.getZ()),
                        new Pos(maxMined.getX(), maxMined.getY(), maxMined.getZ()),
                        matcher.label())
                .orElse(null);
    }

    /**
     * The one search this job may perform when a scan finds no target: starts one child through the port, ticks it
     * over the following server ticks and reports whether the caller should scan once more. The child receives the
     * job's own principal, captured at construction, so no tick site can mint a different authority. Package-private
     * so a headless test can drive it without a world.
     */
    static final class Exploration {
        enum Step {
            /**
             * No search ran, none is available, the budget is spent, or the search failed: use the scan's message or,
             * when the search reported one, its own actionable reason.
             */
            NONE,
            /** The search is still running: the job keeps ticking. */
            RUNNING,
            /** The search succeeded: the caller scans the world once more. */
            RESCAN
        }

        private final BlockSearchTaskFactory factory;
        private final boolean enabled;
        private final Caller caller;
        private Task search;
        private boolean searched;
        private int ticks;
        /** The budget of the running search, captured when the job asked for it. */
        private int limit;
        /** What the search itself said when it failed or spent its budget; blank when it never reported anything. */
        private String failure = "";

        Exploration(BlockSearchTaskFactory factory, boolean enabled, Caller caller) {
            this.factory = factory == null ? BlockSearchTaskFactory.NONE : factory;
            this.enabled = enabled && this.factory != BlockSearchTaskFactory.NONE;
            this.caller = caller;
        }

        boolean searching() {
            return search != null;
        }

        /** How long the running search has waited, or -1 when no search is running. */
        int waitingTicks() {
            return search == null ? -1 : ticks;
        }

        /** The running search's own budget, or zero when no search is running. */
        int waitingLimitTicks() {
            return search == null ? 0 : limit;
        }

        /** The job's principal, captured at construction and never minted by a tick. */
        Caller caller() {
            return caller;
        }

        /** The child's own actionable reason, so the job does not replace it with a generic refusal. */
        String failureSummary() {
            return failure;
        }

        /**
         * Called after a scan found nothing: creates the one search when it is allowed, then ticks it until it
         * finishes or its budget is spent. The budget is the live configured local search budget, so the server
         * owner tunes one bounded search rather than each task keeping a number of its own. At most one search is
         * ever created per job.
         */
        Step missing(TaskContext c, String blockOrTag, int radius, ResourceSearchSettings settings) {
            int maxTicks = settings.localTicks();
            this.limit = maxTicks;
            if (search != null) {
                TaskStatus status;
                try {
                    status = search.tick(c);
                } catch (RuntimeException e) {
                    LOGGER.warn("Block search crashed; the mining job reports its own result", e);
                    terminate(c);
                    return Step.NONE;
                }
                if (status == TaskStatus.RUNNING) {
                    if (++ticks >= maxTicks) {
                        failure = summaryOf(search);
                        terminate(c);
                        return Step.NONE;
                    }
                    return Step.RUNNING;
                }
                Task finished = search;
                search = null;
                if (status != TaskStatus.SUCCESS) {
                    failure = summaryOf(finished);
                }
                release(c, finished);
                return status == TaskStatus.SUCCESS ? Step.RESCAN : Step.NONE;
            }
            if (!enabled || searched) {
                return Step.NONE;
            }
            searched = true;
            Task created = null;
            try {
                created = factory.create(c.companion(), caller, blockOrTag, radius, maxTicks);
                if (created == null) {
                    return Step.NONE;
                }
                created.start(c);
            } catch (RuntimeException e) {
                LOGGER.warn("Block search could not start; the mining job reports its own result", e);
                if (created != null) {
                    safeStop(c, created);
                    release(c, created);
                }
                return Step.NONE;
            }
            search = created;
            ticks = 0;
            return Step.RUNNING;
        }

        void stop(TaskContext c) {
            if (search != null) {
                Task stopping = search;
                search = null;
                safeStop(c, stopping);
            }
        }

        /** Ends a search that outlived its budget, the way a timeout ends a plan step. */
        private void terminate(TaskContext c) {
            Task finished = search;
            search = null;
            if (finished == null) {
                return;
            }
            safeStop(c, finished);
            release(c, finished);
        }

        private static void safeStop(TaskContext c, Task task) {
            try {
                task.stop(c);
            } catch (RuntimeException e) {
                LOGGER.warn("Block search failed to stop", e);
            }
        }

        /** Terminal cleanup for a child that ended on its own; cancellation uses {@link Task#stop}. */
        private static void release(TaskContext c, Task task) {
            try {
                task.onTerminal(c);
            } catch (RuntimeException e) {
                LOGGER.warn("Block search failed terminal cleanup", e);
            }
        }

        private static String summaryOf(Task task) {
            try {
                String text = task.resultSummary();
                return text == null ? "" : text;
            } catch (RuntimeException e) {
                return "";
            }
        }
    }
}
