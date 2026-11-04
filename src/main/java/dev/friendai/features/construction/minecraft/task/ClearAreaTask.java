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
 */
public final class ClearAreaTask implements Task {
    /** One task is bounded so an area asked too large cannot run for an hour. */
    public static final int MAX_CELLS = 256;
    /** How long one cell may keep the companion walking and breaking before it is given up as unreachable. */
    static final int CELL_TICKS = 300;
    /** How many cells in a row may be out of reach before the rest of the area is given up as unreachable. */
    static final int MAX_UNREACHABLE_STREAK = 3;

    private final BlockPos center;
    private final int radius;
    private final int depth;
    private final int height;
    private final Set<ClearKind> wanted;
    private final EnumMap<ClearKind, Integer> cleared = new EnumMap<>(ClearKind.class);
    private final EnumMap<ClearSkip, Integer> skipped = new EnumMap<>(ClearSkip.class);
    private final List<ClearCell> queue = new ArrayList<>();
    private int cellTicks;
    private int unreachableStreak;
    private int planned;
    private int left;
    private int solidsLeft;
    private boolean scanned;
    private String summary = "";
    private BlockBreaker breaker;
    private ClearCell current;
    private Block currentBlock;
    private FireHands fireHands;

    public ClearAreaTask(BlockPos center, int radius, int depth, int height, boolean water, boolean blocks) {
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
        if (current == null) {
            var companion = c.companion();
            int next = ClearPlan.nextIndex(queue, companion.getX(), companion.getEyeY(), companion.getZ());
            if (next < 0) {
                return finish();
            }
            current = queue.remove(next);
            cellTicks = 0;
            if (current.kind() == ClearKind.WATER) {
                scoop(c);
                return TaskStatus.RUNNING;
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
            case BROKEN -> cleared.merge(current.kind(), 1, Integer::sum);
            case GONE -> skip(ClearSkip.CHANGED);
            case PROTECTED -> skip(ClearSkip.PROTECTED);
            case UNREACHABLE -> skip(ClearSkip.UNREACHABLE);
            case OUT_OF_SIGHT -> skip(ClearSkip.OUT_OF_SIGHT);
        }
        unreachableStreak = result == BlockBreaker.State.BROKEN ? 0 : unreachableStreak;
        if (result == BlockBreaker.State.UNREACHABLE && ++unreachableStreak >= MAX_UNREACHABLE_STREAK) {
            // The nearest cells of this kind are all out of reach, so the rest (higher, farther) are no easier.
            skipped.merge(ClearSkip.UNREACHABLE, queue.size(), Integer::sum);
            queue.clear();
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
        result.skipped().forEach((reason, count) -> skipped.merge(reason, count, Integer::sum));
        scanned = true;
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
            cleared.merge(ClearKind.WATER, 1, Integer::sum);
        } else {
            skip(ClearSkip.WATER_LEFT);
        }
    }

    private void skip(ClearSkip reason) {
        skipped.merge(reason, 1, Integer::sum);
    }

    private TaskStatus finish() {
        ClearOutcome outcome = ClearOutcome.of(cleared, skipped, solidsLeft, left + queue.size());
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
