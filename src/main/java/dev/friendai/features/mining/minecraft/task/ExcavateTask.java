package dev.friendai.features.mining.minecraft.task;

import dev.friendai.debug.DebugAction;
import dev.friendai.debug.DebugTarget;
import dev.friendai.debug.WorkCell;
import dev.friendai.debug.WorkKind;
import dev.friendai.debug.WorkSet;
import dev.friendai.debug.WorkSetBudget;
import dev.friendai.debug.WorkStatus;
import dev.friendai.features.mining.MiningBounds;
import dev.friendai.features.mining.excavation.ExcavationProgress;
import dev.friendai.features.mining.excavation.ExcavationReport;
import dev.friendai.features.mining.excavation.ExcavationSpec;
import dev.friendai.features.mining.excavation.ExcavationText;
import dev.friendai.features.mining.minecraft.mining.MiningSession;
import dev.friendai.features.mining.minecraft.mining.TemporaryBlockCleanup;
import dev.friendai.foundation.spatial.Node;
import dev.friendai.foundation.spatial.Pos;
import dev.friendai.foundation.text.UserMessage;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.autonomy.PlaceGuard;
import dev.friendai.task.inventory.DropCollector;
import dev.friendai.task.inventory.ItemMatcher;
import dev.friendai.world.block.MiningToolView;
import dev.friendai.world.block.ScaffoldCleanupTask;
import dev.friendai.world.placement.PlacementLedger;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Mines a bounded region through the shared {@link MiningSession}. The pure {@link ExcavationSpec} owns the shape,
 * order and goal; this adapter only answers world questions, feeds bounded target batches and reports measured
 * counters. Every break therefore passes the same protection, support, drop and tool checks as {@code mine_blocks}.
 */
public final class ExcavateTask implements Task {
    /** Ticks a session may stay in approach or descend without breaking another block before one descent is tried. */
    public static final int STALL_FALLBACK_TICKS = 200;

    private static final int BATCH = 64;
    private static final int UNLOADED_WAIT_TICKS = 40;
    private static final int SWEEP_EVERY_BREAKS = 16;
    private static final int MAX_FAILED_BATCHES = 3;
    /** At most this many fallback descents per task, so a long dig cannot keep bolting a shaft under the region. */
    private static final int MAX_FALLBACK_DESCENTS = 3;

    private final ExcavationSpec spec;
    private final List<Pos> order;
    private int nextIndex;
    private final ExcavationProgress progress;
    private final PlacementLedger placements = new PlacementLedger();
    private final ItemMatcher untilMatcher;
    private final StallFallback stallFallback = new StallFallback();
    private String fallbackReason = "";
    private int fallbackDescents;

    private MiningSession session;
    private DropCollector sweep;
    private boolean finalSweep;
    private int unloadedWait;
    private int failedBatches;
    private BlockPos lastBroken;
    private String summary = "";
    private TemporaryBlockCleanup cleanup;
    private TaskStatus pendingStatus;

    public ExcavateTask(ExcavationSpec spec) {
        this(spec, 0, new ExcavationProgress(), 0);
    }

    public ExcavateTask(ExcavationSpec spec, int nextIndex, ExcavationProgress progress, int fallbackDescents) {
        this.spec = spec;
        this.order = spec.geometry().cells();
        this.nextIndex = Math.max(0, Math.min(nextIndex, order.size()));
        this.progress = progress;
        this.fallbackDescents = Math.max(0, Math.min(fallbackDescents, MAX_FALLBACK_DESCENTS));
        this.untilMatcher =
                spec.hasUntil() ? ItemMatcher.parse(spec.untilItem()).orElse(null) : null;
    }

    public static ExcavateTask resume(
            ExcavationSpec spec, int nextIndex, ExcavationProgress progress, int fallbackDescents) {
        return new ExcavateTask(spec, nextIndex, progress, fallbackDescents);
    }

    ExcavationSpec spec() {
        return spec;
    }

    int nextIndex() {
        return nextIndex;
    }

    ExcavationProgress progress() {
        return progress;
    }

    int fallbackDescents() {
        return fallbackDescents;
    }

    @Override
    public String type() {
        return "excavate";
    }

    @Override
    public void start(TaskContext c) {}

    @Override
    public TaskStatus tick(TaskContext c) {
        if (cleanup != null) {
            TemporaryBlockCleanup.State state = cleanup.tick(c);
            if (state == TemporaryBlockCleanup.State.WORKING) {
                return TaskStatus.RUNNING;
            }
            TemporaryBlockCleanup finished = cleanup;
            cleanup = null;
            if (finished.kept() > 0) {
                summary += "; kept " + finished.kept() + " temporary block(s) under the companion";
            }
            placements.clear();
            TaskStatus status = pendingStatus;
            pendingStatus = null;
            return status;
        }
        TaskStatus status = tickInner(c);
        if (status != TaskStatus.RUNNING && !placements.isEmpty()) {
            cleanup = new TemporaryBlockCleanup(placements);
            pendingStatus = status;
            return TaskStatus.RUNNING;
        }
        return status;
    }

    private TaskStatus tickInner(TaskContext c) {
        progress.markTick();
        if (spec.reserveDurability() > 0 && toolBelowReserve(c)) {
            if (session != null) {
                session.stop(c);
                session = null;
            }
            return finish(
                    TaskStatus.FAILED,
                    ExcavationReport.partial(
                            ExcavationText.label(spec), progress, "the tool is down to the reserved durability"));
        }
        if (sweep != null) {
            if (sweep.tick(c) != DropCollector.State.DONE) {
                return TaskStatus.RUNNING;
            }
            accumulate(sweep.collectedStacks());
            boolean wasFinal = finalSweep;
            sweep = null;
            finalSweep = false;
            if (spec.goal().itemReached(progress.taken())) {
                return finish(TaskStatus.SUCCESS, ExcavationReport.success(ExcavationText.label(spec), progress));
            }
            if (wasFinal) {
                if (spec.hasUntil()) {
                    return finish(
                            TaskStatus.FAILED,
                            ExcavationReport.partial(
                                    ExcavationText.label(spec),
                                    progress,
                                    "took " + progress.taken().getOrDefault(spec.untilItem(), 0) + " of "
                                            + spec.untilCount() + " " + spec.untilItem()));
                }
                return finish(TaskStatus.SUCCESS, ExcavationReport.success(ExcavationText.label(spec), progress));
            }
            return TaskStatus.RUNNING;
        }
        if (session != null) {
            TaskStatus stalled = tickStallFallback(c);
            if (stalled != null) {
                return stalled;
            }
            MiningSession.State state = session.tick(c);
            switch (state) {
                case WORKING:
                    return TaskStatus.RUNNING;
                case EXTRACTED:
                    BlockPos broken = session.target();
                    lastBroken = broken;
                    // A fallback descent also breaks the support cell; only a batched region target is region progress.
                    if (broken == null || session.isCurrentTarget(broken)) {
                        progress.markBroken();
                        fallbackReason = "";
                    }
                    failedBatches = 0;
                    if (progress.broken() >= spec.count()) {
                        session.stop(c);
                        session = null;
                        return startSweep(c, true);
                    }
                    if (spec.collect() && (spec.hasUntil() || progress.broken() % SWEEP_EVERY_BREAKS == 0)) {
                        return startSweep(c, false);
                    }
                    return TaskStatus.RUNNING;
                case EXHAUSTED:
                    session = null;
                    return feed(c);
                case FAILED:
                    UserMessage failure = session.failure();
                    session.stop(c);
                    session = null;
                    String reason = failure == null ? "unknown" : failureText(failure);
                    progress.markRefused(reason);
                    if ("friendai.mining.inventory_full".equals(failure == null ? "" : failure.key())) {
                        return finish(
                                TaskStatus.FAILED,
                                ExcavationReport.partial(
                                        ExcavationText.label(spec), progress, "the inventory is full"));
                    }
                    if (++failedBatches >= MAX_FAILED_BATCHES) {
                        return finish(
                                TaskStatus.FAILED,
                                ExcavationReport.partial(ExcavationText.label(spec), progress, reason));
                    }
                    return feed(c);
            }
        }
        return feed(c);
    }

    /**
     * Feeds the bounded stall rule and runs its one deterministic descent when it fires. A session already walking a
     * route or breaking a block is making progress the stamp cannot show, so the window restarts instead of forcing a
     * second action on top of a running one. Null means the caller should keep ticking the session.
     */
    private TaskStatus tickStallFallback(TaskContext c) {
        if (session.busy()) {
            stallFallback.reset();
            return null;
        }
        if (stallFallback.observe(progress.broken(), session.phase()) != StallFallback.Decision.DIG) {
            return null;
        }
        boolean withinCap = fallbackDescents < MAX_FALLBACK_DESCENTS;
        boolean descended = withinCap
                && session.forceDescent(
                        c, new MiningSession.DescentRules(spec.containers(), spec.allowLiquids(), spec.requireTool()));
        if (descended) {
            fallbackDescents++;
            fallbackReason = "fallback-dig";
            return null;
        }
        boolean capped = !withinCap;
        fallbackReason = capped ? "fallback-cap" : "fallback-refused";
        session.stop(c);
        session = null;
        String reason = capped
                ? failureText(fallbackCap()) + " (fallback descents capped at " + MAX_FALLBACK_DESCENTS + ")"
                : failureText(fallbackRefusal());
        return finish(TaskStatus.FAILED, ExcavationReport.partial(ExcavationText.label(spec), progress, reason));
    }

    private TaskStatus feed(TaskContext c) {
        if (progress.broken() >= spec.count()) {
            return startSweep(c, true);
        }
        if (nextIndex >= order.size()) {
            return startSweep(c, true);
        }
        List<BlockPos> batch = new ArrayList<>(BATCH);
        int limit = Math.min(BATCH, spec.count() - progress.broken());
        boolean unloaded = false;
        while (nextIndex < order.size() && batch.size() < limit) {
            Pos cell = order.get(nextIndex);
            BlockPos pos = new BlockPos(cell.x(), cell.y(), cell.z());
            if (!c.level().hasChunkAt(pos)) {
                unloaded = true;
                break;
            }
            nextIndex++;
            progress.markAttempted();
            BlockState state = c.level().getBlockState(pos);
            if (state.isAir()) {
                progress.markSkipped("already empty");
                continue;
            }
            if (!spec.allowLiquids() && !state.getFluidState().isEmpty()) {
                progress.markSkipped("fluid");
                continue;
            }
            if (!spec.containers() && c.level().getBlockEntity(pos) instanceof Container) {
                progress.markSkipped("container");
                continue;
            }
            if (PlaceGuard.protects(c, pos)) {
                progress.markSkipped("protected");
                continue;
            }
            if (spec.requireTool()
                    && state.requiresCorrectToolForDrops()
                    && MiningToolView.currentToolFor(c.companion(), state).isEmpty()) {
                progress.markSkipped("no tool");
                continue;
            }
            batch.add(pos);
        }
        if (batch.isEmpty()) {
            if (unloaded) {
                if (++unloadedWait > UNLOADED_WAIT_TICKS) {
                    return finish(
                            TaskStatus.FAILED,
                            ExcavationReport.partial(
                                    ExcavationText.label(spec), progress, "the next cells are not loaded"));
                }
                return TaskStatus.RUNNING;
            }
            if (nextIndex >= order.size()) {
                return startSweep(c, true);
            }
            return TaskStatus.RUNNING;
        }
        unloadedWait = 0;
        session = new MiningSession(c.companion(), bounds(), batch.size() + 128, 6_000, placements);
        // A new batch starts a new stall window; ticks accumulated for the previous one say nothing about it.
        stallFallback.reset();
        // The geometry ordered these cells as a passage, so a forward-down cell is the next step of it, not an
        // underfoot resource: carving it from the step above keeps the stair inside its own cross-section.
        session.setTargets(batch, state -> !state.isAir(), true);
        return TaskStatus.RUNNING;
    }

    private MiningBounds bounds() {
        Pos first = order.isEmpty() ? Pos.of(0, 0, 0) : order.get(0);
        int radius = 8;
        for (Pos cell : order) {
            radius = Math.max(radius, Math.max(Math.abs(cell.x() - first.x()), Math.abs(cell.z() - first.z())) + 8);
        }
        return new MiningBounds(new Node(first.x(), first.y(), first.z()), Math.min(128, radius));
    }

    private TaskStatus startSweep(TaskContext c, boolean last) {
        if (!spec.collect() && !last) {
            return TaskStatus.RUNNING;
        }
        if (last && !spec.collect()) {
            return finish(TaskStatus.SUCCESS, ExcavationReport.success(ExcavationText.label(spec), progress));
        }
        BlockPos from = lastBroken != null
                ? lastBroken
                : new BlockPos(
                        order.isEmpty() ? 0 : order.get(0).x(),
                        order.isEmpty() ? 0 : order.get(0).y(),
                        order.isEmpty() ? 0 : order.get(0).z());
        sweep = new DropCollector(Vec3.atCenterOf(from), 8, last ? null : untilMatcher);
        finalSweep = last;
        return TaskStatus.RUNNING;
    }

    private boolean toolBelowReserve(TaskContext c) {
        ItemStack hand = c.companion().getMainHandItem();
        if (hand.isEmpty() || !hand.isDamageableItem()) {
            return false;
        }
        int remaining = hand.getMaxDamage() - hand.getDamageValue();
        return remaining * 100 <= hand.getMaxDamage() * spec.reserveDurability();
    }

    private void accumulate(List<ItemStack> stacks) {
        for (ItemStack stack : stacks) {
            if (!stack.isEmpty()) {
                progress.addTaken(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount());
            }
        }
    }

    private TaskStatus finish(TaskStatus status, String text) {
        summary = text;
        pendingStatus = status;
        return status;
    }

    static String failureText(UserMessage failure) {
        return switch (failure.key()) {
            case "friendai.mining.no_tool" -> "no suitable tool for the next block";
            case "friendai.mining.unsafe_support" -> "a cell supports the companion or its route";
            case "friendai.mining.server_protected" -> "a cell is protected by the server";
            case "friendai.mining.place_protected" -> "a cell is inside a protected place";
            case "friendai.mining.own_support_unsafe" -> "a cell is under the companion and its drop is unsafe";
            case "friendai.mining.not_breakable" -> "a cell cannot be broken";
            case "friendai.mining.unsafe_passage" -> "the passage is unsafe";
            case "friendai.mining.unreachable" -> "a cell cannot be reached safely";
            case "friendai.mining.search_limit" -> "the search limit was reached";
            case "friendai.mining.inventory_full" -> "the inventory is full";
            default -> failure.key();
        };
    }

    /** The typed refusal a fallback that cannot dig reports, shared with the tests that pin it. */
    static UserMessage fallbackRefusal() {
        return UserMessage.of("friendai.mining.unreachable");
    }

    /**
     * The typed message a fallback that spent its descent cap reports. The cap is not a safety refusal, so it reuses
     * the existing search-limit wording instead of pretending a cell was unreachable.
     */
    static UserMessage fallbackCap() {
        return UserMessage.of("friendai.mining.search_limit");
    }

    /**
     * The bounded stall rule: {@link #STALL_FALLBACK_TICKS} ticks without another broken block while the session is
     * planning an approach or descent. It is pure so the rule is unit-tested without a world; the break itself is
     * {@link MiningSession#forceDescent(TaskContext, MiningSession.DescentRules)}.
     */
    static final class StallFallback {
        /** Where one stalled tick leads: keep waiting, or try the one bounded descent below the companion. */
        enum Decision {
            WAIT,
            DIG
        }

        private long stamp = Long.MIN_VALUE;
        private int idleTicks;

        Decision observe(long stamp, String phase) {
            if (stamp != this.stamp) {
                this.stamp = stamp;
                idleTicks = 0;
            }
            if (!"approach".equals(phase) && !"descend".equals(phase)) {
                idleTicks = 0;
                return Decision.WAIT;
            }
            if (++idleTicks < STALL_FALLBACK_TICKS) {
                return Decision.WAIT;
            }
            idleTicks = 0;
            return Decision.DIG;
        }

        /** How long the stalled window has been open, or -1 when the session is not waiting (yet). */
        int waitingTicks() {
            return idleTicks > 0 ? idleTicks : -1;
        }

        void reset() {
            idleTicks = 0;
        }
    }

    @Override
    public List<DebugTarget> debugTargets() {
        List<DebugTarget> targets = new ArrayList<>();
        BlockPos target = session == null ? null : session.target();
        if (target == null && nextIndex < order.size()) {
            Pos pending = order.get(nextIndex);
            target = new BlockPos(pending.x(), pending.y(), pending.z());
        }
        if (target != null) {
            targets.add(new DebugTarget(target.getX(), target.getY(), target.getZ(), DebugAction.BREAK, "Excavate"));
        }
        if (session != null) {
            for (BlockPos cell : session.catchCells()) {
                targets.add(new DebugTarget(cell.getX(), cell.getY(), cell.getZ(), DebugAction.PLACE, "catch"));
            }
        }
        return targets;
    }

    @Override
    public List<WorkSet> debugWorkSets() {
        if (order.isEmpty()) {
            return List.of();
        }
        BlockPos next = session == null ? null : session.target();
        if (next == null && nextIndex < order.size()) {
            Pos pending = order.get(nextIndex);
            next = new BlockPos(pending.x(), pending.y(), pending.z());
        }
        int total = order.size();
        int done = Math.min(progress.broken(), total);
        int skipped = Math.min(progress.skipped(), total - done);
        int nextCount = next == null ? 0 : Math.min(1, total - done - skipped);
        int pending = total - done - skipped - nextCount;
        List<WorkCell> cells = new ArrayList<>(Math.min(WorkSetBudget.MAX_WORK_CELLS, total));
        if (nextCount == 1) {
            cells.add(new WorkCell(
                    next.getX(), next.getY(), next.getZ(), DebugAction.BREAK, WorkStatus.NEXT, "Excavate"));
        }
        int pendingAdded = 0;
        if (session != null) {
            for (BlockPos pos : session.targets()) {
                if (pendingAdded >= pending || cells.size() >= WorkSetBudget.MAX_WORK_CELLS) break;
                if (pos.equals(next)) continue;
                cells.add(new WorkCell(
                        pos.getX(), pos.getY(), pos.getZ(), DebugAction.BREAK, WorkStatus.PENDING, "Excavate"));
                pendingAdded++;
            }
        }
        for (int i = nextIndex; i < total; i++) {
            if (pendingAdded >= pending || cells.size() >= WorkSetBudget.MAX_WORK_CELLS) break;
            Pos pos = order.get(i);
            if (next != null && pos.x() == next.getX() && pos.y() == next.getY() && pos.z() == next.getZ()) {
                continue;
            }
            cells.add(new WorkCell(pos.x(), pos.y(), pos.z(), DebugAction.BREAK, WorkStatus.PENDING, "Excavate"));
            pendingAdded++;
        }
        return List.of(new WorkSet(
                ExcavationText.label(spec),
                WorkKind.BLOCKS,
                total,
                done,
                nextCount,
                pending,
                skipped,
                cells.size() < total,
                cells));
    }

    @Override
    public String debugStatus() {
        StringBuilder text = new StringBuilder("excavate ")
                .append(progress.broken())
                .append('/')
                .append(spec.count())
                .append(" broken, skipped ")
                .append(progress.skipped());
        if (sweep != null) {
            text.append(", collecting drops");
        }
        if (cleanup != null) {
            text.append(", cleaning up temporary blocks");
        }
        if (session != null) {
            text.append(", ").append(session.phase());
            String reason = session.debugReason();
            if (!reason.isEmpty()) {
                text.append(", ").append(reason);
            }
        }
        if (!fallbackReason.isEmpty()) {
            text.append(", ").append(fallbackReason);
        }
        return text.toString();
    }

    @Override
    public void stop(TaskContext c) {
        if (session != null) {
            session.stop(c);
            session = null;
        }
        if (sweep != null) {
            sweep.stop(c);
            sweep = null;
        }
        if (cleanup != null) {
            cleanup.stop(c);
            cleanup = null;
        }
    }

    @Override
    public Task cleanupAfterCancel(TaskContext c) {
        return placements.isEmpty() ? null : new ScaffoldCleanupTask(placements.positions());
    }

    @Override
    public long progressStamp() {
        return progress.broken();
    }

    /**
     * The ticks before the one bounded descent are a wait the job itself measures, not a stall: the rule needs its
     * full window to decide, so the runner's generic stall limit must not end the job first.
     */
    @Override
    public int waitingTicks() {
        return session == null || session.busy() ? -1 : stallFallback.waitingTicks();
    }

    @Override
    public int waitingLimitTicks() {
        return waitingTicks() >= 0 ? STALL_FALLBACK_TICKS : 0;
    }

    @Override
    public String describe() {
        return ExcavationText.describe(spec);
    }

    @Override
    public String resultSummary() {
        return summary;
    }

    @Override
    public String saveData() {
        return ExcavationCheckpoints.encode(this);
    }
}
