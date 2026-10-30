package dev.friendai.features.mining.minecraft.task;

import dev.friendai.common.Components;
import dev.friendai.debug.DebugAction;
import dev.friendai.debug.DebugTarget;
import dev.friendai.debug.WorkCell;
import dev.friendai.debug.WorkKind;
import dev.friendai.debug.WorkSet;
import dev.friendai.debug.WorkStatus;
import dev.friendai.features.mining.MiningBounds;
import dev.friendai.features.mining.minecraft.mining.BlockBreaker;
import dev.friendai.features.mining.minecraft.mining.BlockMatcher;
import dev.friendai.features.mining.minecraft.mining.CatchPlatform;
import dev.friendai.features.mining.minecraft.mining.DropGuard;
import dev.friendai.features.mining.minecraft.mining.DropRoom;
import dev.friendai.features.mining.minecraft.mining.MiningRefusal;
import dev.friendai.features.mining.minecraft.mining.MiningSession;
import dev.friendai.features.mining.minecraft.mining.TemporaryBlockCleanup;
import dev.friendai.features.navigation.minecraft.nav.PlacementLedger;
import dev.friendai.features.navigation.pathing.Node;
import dev.friendai.features.navigation.pathing.Pos;
import dev.friendai.features.places.PlaceAutoRegistration;
import dev.friendai.features.places.PlaceCandidate;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.inventory.DropCollector;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Finds, walks to, breaks N blocks of a kind and sweeps up what they drop. */
public final class MineBlocksTask implements Task {
    private final BlockMatcher matcher;
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
    /** The first block this job actually broke; the anchor of the place it may be remembered as. */
    private BlockPos firstMined;
    /** The dimension the job ran in, captured at start because a place candidate is built without a context. */
    private String dimension = "";
    /** {@code places.autoMineMinBlocks}, captured at start: a small dig is not a mine. */
    private int autoMinBlocks = Integer.MAX_VALUE;

    /** Where to search from; null means around the companion. */
    private final BlockPos origin;
    /** Whether to walk around picking the drops up afterwards; drops at the feet are always taken. */
    private final boolean collect;
    /** When true, candidates come only from the placement ledger; unplaced siblings are never mined. */
    private final boolean placedOnly;

    public MineBlocksTask(BlockMatcher matcher, int count, int radius) {
        this(matcher, count, radius, null, true, false);
    }

    public MineBlocksTask(BlockMatcher matcher, int count, int radius, BlockPos origin, boolean collect) {
        this(matcher, count, radius, origin, collect, false);
    }

    public MineBlocksTask(
            BlockMatcher matcher, int count, int radius, BlockPos origin, boolean collect, boolean placedOnly) {
        this.matcher = matcher;
        this.count = count;
        this.radius = radius;
        this.origin = origin;
        this.collect = collect;
        this.placedOnly = placedOnly;
    }

    @Override
    public String type() {
        return "mine_blocks";
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
            if (sweep.tick(c) == DropCollector.State.DONE) {
                sweep = null;
            }
            return TaskStatus.RUNNING;
        }
        if (miningSessionActive) {
            if (miningSession.target() != null
                    && "extract".equals(miningSession.phase())
                    && !DropRoom.hasRoom(c, miningSession.target())) {
                miningSession.stop(c);
                miningSessionActive = false;
                summary = "mined " + mined + " of " + count + ", inventory full before the next drop";
                c.owner()
                        .ifPresent(player -> player.sendSystemMessage(
                                Components.of(
                                        dev.friendai.foundation.text.UserMessage.of("friendai.mining.inventory_full")),
                                true));
                return TaskStatus.FAILED;
            }
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
                mined++;
                rememberFirst(lastBroken);
                c.placedService().forget(c.companion(), lastBroken);
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
                        dev.friendai.features.mining.DropRisk.Reason.PROTECTED_CELL,
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

    private TaskStatus tickMiningSession(TaskContext c) {
        MiningSession.State state = miningSession.tick(c);
        return switch (state) {
            case WORKING -> TaskStatus.RUNNING;
            case EXTRACTED -> {
                lastBroken = miningSession.target();
                pendingObservation = lastBroken;
                observationWaitTicks = 0;
                mined++;
                rememberFirst(lastBroken);
                c.placedService().forget(c.companion(), lastBroken);
                miningSessionActive = false;
                yield TaskStatus.RUNNING;
            }
            case EXHAUSTED -> {
                miningSessionActive = false;
                summary = "mined " + mined + " of " + count + ", mining search limit reached";
                yield TaskStatus.FAILED;
            }
            case FAILED -> {
                miningSessionActive = false;
                String reason = miningSession.failure() == null
                        ? "the resource became unreachable"
                        : failureText(miningSession.failure());
                summary = "mined " + mined + " of " + count + ", " + reason;
                yield TaskStatus.FAILED;
            }
        };
    }

    private TaskStatus search(TaskContext c) {
        if (mined >= count) {
            if (startSweep()) {
                return TaskStatus.RUNNING;
            }
            summary = "mined " + mined + " " + matcher.label();
            return TaskStatus.SUCCESS;
        }
        List<BlockPos> candidates;
        if (placedOnly) {
            candidates = new ArrayList<>(
                    c.placedService().candidates(c.companion(), matcher, origin, radius, evidenceLimit(CANDIDATES)));
            candidates.removeIf(this::excluded);
        } else {
            candidates = treeCandidates(c);
            if (candidates.isEmpty()) {
                if (startSweep()) {
                    // The tree is finished: pick its drops up before going anywhere else.
                    return TaskStatus.RUNNING;
                }
                var evidence = c.resources();
                int evidenceRadius = origin == null
                        ? radius
                        : Math.min(128, c.companion().blockPosition().distManhattan(origin) + radius);
                candidates = new ArrayList<>(evidence.knownOrVisible(
                        c.companion(), matcher, evidenceRadius, evidenceLimit(origin == null ? CANDIDATES : 64)));
                candidates.removeIf(this::excluded);
                if (origin != null) {
                    double radiusSquared = (double) radius * radius;
                    double originX = origin.getX() + 0.5, originY = origin.getY() + 0.5, originZ = origin.getZ() + 0.5;
                    candidates.removeIf(pos -> pos.distToCenterSqr(originX, originY, originZ) > radiusSquared);
                    candidates.sort(java.util.Comparator.comparingDouble(
                            pos -> pos.distToCenterSqr(originX, originY, originZ)));
                    if (candidates.size() > 1) {
                        // BlockBreaker walks from the companion, so choose the nearest eligible evidence to the
                        // requested origin before handing it a single target.
                        candidates = new ArrayList<>(candidates.subList(0, 1));
                    }
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
            // Say which it is: nothing around, or blocks that were found and could not be reached.
            boolean hidden = !blacklist.isEmpty();
            if (mined == 0) {
                summary = placedOnly
                        ? "no " + matcher.label() + " you placed nearby"
                        : hidden
                                ? "could not reach any " + matcher.label() + " nearby"
                                : "no " + matcher.label()
                                        + " observed or remembered nearby; use look_around or survey_area to look around";
            } else {
                summary = "mined " + mined + " of " + count + ", no more " + matcher.label()
                        + (placedOnly
                                ? " you placed nearby"
                                : hidden ? " could be reached" : " observed or remembered nearby");
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
                BlockPos start = origin != null ? origin : c.companion().blockPosition();
                miningSession = new MiningSession(
                        c.companion(),
                        new MiningBounds(new Node(start.getX(), start.getY(), start.getZ()), radius),
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
            case "friendai.mining.inventory_full" -> "the inventory is full";
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
        lastBroken = null;
        return true;
    }

    /**
     * The blocks of the same tree or vein as the last mined one, so one tree is felled before the next is started.
     * Empty when there is no last block or its surroundings hold nothing of the kind.
     */
    private List<BlockPos> treeCandidates(TaskContext c) {
        if (lastBroken == null) {
            return new ArrayList<>();
        }
        List<BlockPos> seeds = new ArrayList<>(6);
        for (net.minecraft.core.Direction side : net.minecraft.core.Direction.values()) {
            seeds.add(lastBroken.relative(side));
        }
        var evidence = c.resources();
        List<BlockPos> result =
                new ArrayList<>(evidence.knownOrVisible(c.companion(), matcher, radius, evidenceLimit(CANDIDATES)));
        result.removeIf(pos -> seeds.stream().noneMatch(seed -> seed.distManhattan(pos) <= 1));
        result.removeIf(this::excluded);
        return result.size() <= CANDIDATES ? result : new ArrayList<>(result.subList(0, CANDIDATES));
    }

    private boolean excluded(BlockPos pos) {
        return blacklist.contains(pos) || miningSession != null && miningSession.protects(pos);
    }

    private int evidenceLimit(int wanted) {
        int supports = miningSession == null ? 0 : miningSession.protectedSupportCount();
        return wanted + blacklist.size() + supports;
    }

    @Override
    public void stop(TaskContext c) {
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

    @Override
    public String describe() {
        return "mining " + count + " " + matcher.label()
                + (origin != null ? " around " + origin.getX() + " " + origin.getY() + " " + origin.getZ() : "")
                + (pendingObservation != null && observationWaitTicks > 0 ? " (checking exposed blocks)" : "");
    }

    @Override
    public String resultSummary() {
        return summary;
    }

    /** The first block broken keeps the place anchored where the ore or stone was, not where the search started. */
    private void rememberFirst(BlockPos minedBlock) {
        if (firstMined == null) {
            firstMined = minedBlock;
        }
    }

    /**
     * The dug-out site, only once the job was big enough to be a mine. Anchored on the requested origin when the call
     * named one, else on the first block broken; the radius is the job's own search radius.
     */
    @Override
    public PlaceCandidate placeCandidate() {
        BlockPos anchor = origin != null ? origin : firstMined;
        if (anchor == null) {
            return null;
        }
        return PlaceAutoRegistration.mineCandidate(
                        mined,
                        autoMinBlocks,
                        dimension,
                        new Pos(anchor.getX(), anchor.getY(), anchor.getZ()),
                        radius,
                        matcher.label())
                .orElse(null);
    }
}
