package dev.friendai.features.construction.minecraft.task;

import dev.friendai.debug.DebugAction;
import dev.friendai.debug.DebugTarget;
import dev.friendai.features.construction.blueprint.ClearCell;
import dev.friendai.features.construction.blueprint.ClearKind;
import dev.friendai.features.construction.blueprint.ClearPlan;
import dev.friendai.features.construction.clear.ClearOutcome;
import dev.friendai.features.construction.clear.ClearSkip;
import dev.friendai.features.construction.minecraft.build.ClearScan;
import dev.friendai.features.fire.minecraft.FireHands;
import dev.friendai.features.mining.minecraft.mining.BlockBreaker;
import dev.friendai.features.navigation.minecraft.nav.Approach;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Clears a box around a point: vegetation and snow by default, water sources with an empty bucket, and stray solid
 * blocks only when asked. The work list is {@link ClearPlan}; every non-water cell is broken through
 * {@link BlockBreaker}, so reach, line of sight, the server's protection, place protection and the break blacklist
 * still apply. Chests, furnaces and other block entities are never touched; neither is the block the companion
 * stands on.
 *
 * <p>One run is bounded in time: {@link #DEFAULT_TICK_BUDGET} counted ticks of cell work across all cells, plus at most
 * two ticks around the stop (the scan tick is not counted and the budget is checked while a cell is active), and
 * {@link #CELL_TICKS} ticks per cell. An area asked too large therefore cannot hold the companion for an hour, and a
 * run that spends its budget reports the cells it did not reach for another pass.
 */
public final class ClearAreaTask implements Task {
    /** One task is bounded so an area asked too large cannot run for an hour. */
    public static final int MAX_CELLS = 256;
    /** The total ticks one run may spend across all cells, so a large order cannot hold the companion indefinitely. */
    public static final int DEFAULT_TICK_BUDGET = 6_000;
    /** How long one cell may keep the companion walking and breaking before it is given up as unreachable. */
    static final int CELL_TICKS = 300;
    /** How many cells in a row may be out of reach before the rest of the area is given up as unreachable. */
    static final int MAX_UNREACHABLE_STREAK = 3;

    private final BlockPos center;
    private final int radius;
    private final int depth;
    private final int height;
    private final Set<ClearKind> wanted;
    private final int tickBudget;
    private final EnumMap<ClearKind, Integer> cleared = new EnumMap<>(ClearKind.class);
    private final EnumMap<ClearSkip, Integer> skipped = new EnumMap<>(ClearSkip.class);
    private final List<ClearCell> queue = new ArrayList<>();
    private int clearedTotal;
    private int skippedTotal;
    private int cellTicks;
    private int ticksSpent;
    private int unreachableStreak;
    private int planned;
    private int left;
    private int solidsLeft;
    private boolean scanned;
    private String summary = "";
    private BlockBreaker breaker;
    private Approach waterApproach;
    private ClearCell current;
    private Block currentBlock;
    private FireHands fireHands;

    public ClearAreaTask(BlockPos center, int radius, int depth, int height, boolean water, boolean blocks) {
        this(center, radius, depth, height, water, blocks, DEFAULT_TICK_BUDGET);
    }

    /**
     * @param tickBudget total ticks one run may spend across all cells; exposed so a test can hold a run to a small
     *     budget instead of waiting minutes
     */
    public ClearAreaTask(
            BlockPos center, int radius, int depth, int height, boolean water, boolean blocks, int tickBudget) {
        this.tickBudget = Math.max(1, tickBudget);
        this.center = center;
        this.radius = Math.clamp(radius, 1, 16);
        this.depth = Math.clamp(depth, 0, 8);
        this.height = Math.clamp(height, 1, 16);
        this.wanted = EnumSet.of(ClearKind.VEGETATION, ClearKind.SNOW);
        if (water) {
            wanted.add(ClearKind.WATER);
        }
        if (blocks) {
            wanted.add(ClearKind.BLOCK);
        }
    }

    @Override
    public String type() {
        return "clear_area";
    }

    @Override
    public boolean hasTimeout() {
        return false;
    }

    @Override
    public void start(TaskContext c) {}

    @Override
    public TaskStatus tick(TaskContext c) {
        if (!scanned) {
            scan(c);
            return TaskStatus.RUNNING;
        }
        ticksSpent++;
        if (ticksSpent >= tickBudget && current != null) {
            // The budget ran out with a cell in hand: stop its walk or break and report the rest for another pass.
            stop(c);
            TaskStatus status = finish();
            current = null;
            currentBlock = null;
            return status;
        }
        if (current == null) {
            var companion = c.companion();
            int next = ClearPlan.nextIndex(queue, companion.getX(), companion.getEyeY(), companion.getZ());
            if (next < 0) {
                return finish();
            }
            current = queue.remove(next);
            cellTicks = 0;
            if (current.kind() == ClearKind.WATER) {
                return approachWater(c);
            }
            BlockPos pos = pos(current);
            BlockState state = c.level().getBlockState(pos);
            if (state.isAir()) {
                skip(ClearSkip.CHANGED);
                current = null;
                return TaskStatus.RUNNING;
            }
            currentBlock = state.getBlock();
            breaker = new BlockBreaker(pos, s -> s.is(currentBlock));
            return TaskStatus.RUNNING;
        }
        if (current.kind() == ClearKind.WATER) {
            return approachWater(c);
        }
        BlockBreaker.State result = breaker.tick(c);
        if (result == BlockBreaker.State.WORKING && ++cellTicks > CELL_TICKS) {
            // A cell the route cannot bring the companion to (the top of a tree) is left for the rest of the area.
            breaker.stop(c);
            result = BlockBreaker.State.UNREACHABLE;
        }
        switch (result) {
            case WORKING -> {
                return TaskStatus.RUNNING;
            }
            case BROKEN -> {
                recordCleared(current.kind());
                unreachableStreak = 0;
            }
            case GONE -> skip(ClearSkip.CHANGED);
            case PROTECTED -> skip(ClearSkip.PROTECTED);
            case UNREACHABLE -> unreachable();
            case OUT_OF_SIGHT -> skip(ClearSkip.OUT_OF_SIGHT);
        }
        breaker = null;
        current = null;
        currentBlock = null;
        return TaskStatus.RUNNING;
    }

    private void scan(TaskContext c) {
        ServerLevel level = c.level();
        BlockPos min = center.offset(-radius, -depth, -radius);
        BlockPos max = center.offset(radius, height, radius);
        ClearScan.Result result = ClearScan.scan(
                level,
                c.companion(),
                min,
                max,
                wanted.contains(ClearKind.WATER),
                wanted.contains(ClearKind.BLOCK),
                c.breakList());
        ClearPlan plan = ClearPlan.of(result.candidates(), wanted, MAX_CELLS);
        queue.addAll(plan.cells());
        planned = plan.cells().size();
        left = plan.skipped();
        solidsLeft = result.solidBlocksLeft();
        result.skipped().forEach(this::skip);
        scanned = true;
    }

    /**
     * Walks to a water source before scooping it, so a source farther than the bucket's reach is still taken instead
     * of being reported as water left behind. The walk shares the cell's {@link #CELL_TICKS} budget with the break.
     */
    private TaskStatus approachWater(TaskContext c) {
        if (waterApproach == null) {
            BlockPos target = pos(current);
            BlockState state = c.level().getBlockState(target);
            if (!state.is(Blocks.WATER) || !state.getFluidState().isSource()) {
                // The source drained, froze or was taken between the scan and now, so the walk would prove nothing.
                skip(ClearSkip.CHANGED);
                current = null;
                return TaskStatus.RUNNING;
            }
            if (fireHands == null) {
                fireHands = new FireHands(c);
            }
            // No empty bucket means no walk: the first source already filled it, and every later one would fail the
            // same way after the trip.
            if (!fireHands.hasEmptyBucket()) {
                skip(ClearSkip.NO_BUCKET);
                current = null;
                return TaskStatus.RUNNING;
            }
            waterApproach = new Approach(target);
        }
        if (++cellTicks > CELL_TICKS) {
            waterApproach.stop(c);
            waterApproach = null;
            unreachable();
            current = null;
            return TaskStatus.RUNNING;
        }
        switch (waterApproach.tick(c)) {
            case MOVING -> {
                return TaskStatus.RUNNING;
            }
            case UNREACHABLE -> {
                waterApproach.stop(c);
                waterApproach = null;
                unreachable();
                current = null;
            }
            case IN_REACH -> {
                waterApproach = null;
                scoop(c);
            }
        }
        return TaskStatus.RUNNING;
    }

    /** Takes one water source with an empty bucket, through the same path that puts fires out. */
    private void scoop(TaskContext c) {
        BlockPos pos = pos(current);
        BlockState state = c.level().getBlockState(pos);
        current = null;
        if (!state.is(Blocks.WATER) || !state.getFluidState().isSource()) {
            skip(ClearSkip.CHANGED);
            return;
        }
        if (!c.companion().hands().canInteract(pos)) {
            skip(ClearSkip.PROTECTED);
            return;
        }
        if (fireHands == null) {
            fireHands = new FireHands(c);
        }
        if (!fireHands.hasEmptyBucket()) {
            skip(ClearSkip.NO_BUCKET);
            return;
        }
        if (fireHands.recoverSource(pos) == null) {
            recordCleared(ClearKind.WATER);
        } else {
            skip(ClearSkip.WATER_LEFT);
        }
    }

    private void recordCleared(ClearKind kind) {
        cleared.merge(kind, 1, Integer::sum);
        clearedTotal++;
    }

    private void skip(ClearSkip reason) {
        skip(reason, 1);
    }

    private void skip(ClearSkip reason, int count) {
        skipped.merge(reason, count, Integer::sum);
        skippedTotal += count;
    }

    /**
     * Records one cell the companion could not reach, and after {@link #MAX_UNREACHABLE_STREAK} in a row gives up on
     * the rest of the queue: the nearest remaining cells are no easier than the ones just skipped.
     */
    private void unreachable() {
        skip(ClearSkip.UNREACHABLE);
        if (++unreachableStreak >= MAX_UNREACHABLE_STREAK) {
            skip(ClearSkip.UNREACHABLE, queue.size());
            queue.clear();
        }
    }

    private TaskStatus finish() {
        // The active cell has already been removed from the queue, so the cells still ahead of the next pass must
        // count it by hand.
        int cellsLeft = left + queue.size() + (current == null ? 0 : 1);
        ClearOutcome outcome = ClearOutcome.of(cleared, skipped, solidsLeft, cellsLeft);
        if (outcome.nothingToDo()) {
            summary = "nothing to clear around " + coords(center);
            return TaskStatus.SUCCESS;
        }
        StringBuilder text = new StringBuilder("cleared ")
                .append(outcome.clearedTotal())
                .append(outcome.clearedTotal() == 1 ? " block" : " blocks")
                .append(" around ")
                .append(coords(center));
        List<String> kinds = outcome.clearedKinds().stream()
                .map(kind -> outcome.cleared().get(kind) + " " + kind.name().toLowerCase(Locale.ROOT))
                .toList();
        if (!kinds.isEmpty()) {
            text.append(": ").append(String.join(", ", kinds));
        }
        appendReasons(text, "skipped", outcome.failedTotal(), outcome.failed());
        appendReasons(text, "left", outcome.leftByDesignTotal(), outcome.leftByDesign());
        if (outcome.solidBlocksLeft() > 0) {
            text.append("; ")
                    .append(outcome.solidBlocksLeft())
                    .append(" solid blocks left (use blocks=true to break them)");
        }
        if (outcome.cellsLeftForNextPass() > 0) {
            text.append("; ").append(outcome.cellsLeftForNextPass()).append(" more cells left for another pass");
        }
        summary = text.toString();
        return outcome.failedRun() ? TaskStatus.FAILED : TaskStatus.SUCCESS;
    }

    private static void appendReasons(StringBuilder text, String verb, int total, Map<ClearSkip, Integer> counts) {
        if (total <= 0) {
            return;
        }
        List<String> reasons = new ArrayList<>();
        for (ClearSkip reason : ClearSkip.values()) {
            int count = counts.getOrDefault(reason, 0);
            if (count > 0) {
                reasons.add(count + " " + reason.label());
            }
        }
        text.append("; ")
                .append(verb)
                .append(' ')
                .append(total)
                .append(" (")
                .append(String.join(", ", reasons))
                .append(')');
    }

    private static BlockPos pos(ClearCell cell) {
        return new BlockPos(cell.x(), cell.y(), cell.z());
    }

    private static String coords(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    @Override
    public void stop(TaskContext c) {
        if (waterApproach != null) {
            waterApproach.stop(c);
            waterApproach = null;
        }
        if (breaker != null) {
            breaker.stop(c);
            breaker = null;
        }
    }

    @Override
    public String describe() {
        return (planned == 0 ? "clearing the area" : "clearing " + planned + " cells") + " around " + coords(center);
    }

    @Override
    public long progressStamp() {
        return (long) clearedTotal + skippedTotal + (current == null ? 0 : 1);
    }

    @Override
    public String debugStatus() {
        return "clearing " + (planned - queue.size()) + "/" + planned + ", " + clearedTotal + " cleared, "
                + skippedTotal + " skipped";
    }

    @Override
    public List<DebugTarget> debugTargets() {
        if (current == null) {
            return List.of();
        }
        BlockPos pos = pos(current);
        return List.of(new DebugTarget(pos.getX(), pos.getY(), pos.getZ(), DebugAction.BREAK, "Clear area"));
    }

    @Override
    public String resultSummary() {
        return summary;
    }
}
