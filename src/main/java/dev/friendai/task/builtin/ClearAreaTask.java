package dev.friendai.task.builtin;

import dev.friendai.build.ClearCell;
import dev.friendai.build.ClearKind;
import dev.friendai.build.ClearPlan;
import dev.friendai.debug.DebugAction;
import dev.friendai.debug.DebugTarget;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.build.ClearScan;
import dev.friendai.task.fire.FireHands;
import dev.friendai.task.mining.BlockBreaker;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
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

    private final BlockPos center;
    private final int radius;
    private final int depth;
    private final int height;
    private final Set<ClearKind> wanted;
    private final EnumMap<ClearKind, Integer> cleared = new EnumMap<>(ClearKind.class);
    private final EnumMap<ClearScan.SkipReason, Integer> skipped = new EnumMap<>(ClearScan.SkipReason.class);
    private final Deque<ClearCell> queue = new ArrayDeque<>();

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
            current = queue.poll();
            if (current == null) {
                return finish();
            }
            if (current.kind() == ClearKind.WATER) {
                scoop(c);
                return TaskStatus.RUNNING;
            }
            BlockPos pos = pos(current);
            BlockState state = c.level().getBlockState(pos);
            if (state.isAir()) {
                skip(ClearScan.SkipReason.CHANGED);
                current = null;
                return TaskStatus.RUNNING;
            }
            currentBlock = state.getBlock();
            breaker = new BlockBreaker(pos, s -> s.is(currentBlock));
            return TaskStatus.RUNNING;
        }
        BlockBreaker.State result = breaker.tick(c);
        switch (result) {
            case WORKING -> {
                return TaskStatus.RUNNING;
            }
            case BROKEN -> cleared.merge(current.kind(), 1, Integer::sum);
            case GONE -> skip(ClearScan.SkipReason.CHANGED);
            case PROTECTED -> skip(ClearScan.SkipReason.PROTECTED);
            case UNREACHABLE -> skip(ClearScan.SkipReason.UNREACHABLE);
            case OUT_OF_SIGHT -> skip(ClearScan.SkipReason.OUT_OF_SIGHT);
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
                c.serverSettings().breakList());
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
            skip(ClearScan.SkipReason.CHANGED);
            return;
        }
        if (!c.companion().hands().canInteract(pos)) {
            skip(ClearScan.SkipReason.PROTECTED);
            return;
        }
        if (fireHands == null) {
            fireHands = new FireHands(c);
        }
        if (!fireHands.hasEmptyBucket()) {
            skip(ClearScan.SkipReason.NO_BUCKET);
            return;
        }
        if (fireHands.recoverSource(pos) == null) {
            cleared.merge(ClearKind.WATER, 1, Integer::sum);
        } else {
            skip(ClearScan.SkipReason.WATER_LEFT);
        }
    }

    private void skip(ClearScan.SkipReason reason) {
        skipped.merge(reason, 1, Integer::sum);
    }

    private TaskStatus finish() {
        int total = cleared.values().stream().mapToInt(Integer::intValue).sum();
        int skippedTotal = skipped.values().stream().mapToInt(Integer::intValue).sum();
        StringBuilder text = new StringBuilder("cleared ")
                .append(total)
                .append(total == 1 ? " block" : " blocks")
                .append(" around ")
                .append(coords(center));
        List<String> kinds = cleared.entrySet().stream()
                .filter(entry -> entry.getValue() > 0)
                .map(entry -> entry.getValue() + " " + entry.getKey().name().toLowerCase(java.util.Locale.ROOT))
                .toList();
        if (!kinds.isEmpty()) {
            text.append(": ").append(String.join(", ", kinds));
        }
        if (skippedTotal > 0) {
            List<String> reasons = skipped.entrySet().stream()
                    .filter(entry -> entry.getValue() > 0)
                    .map(entry -> entry.getValue() + " " + entry.getKey().label())
                    .toList();
            text.append("; skipped ")
                    .append(skippedTotal)
                    .append(" (")
                    .append(String.join(", ", reasons))
                    .append(')');
        }
        if (solidsLeft > 0) {
            text.append("; ").append(solidsLeft).append(" solid blocks left (use blocks=true to break them)");
        }
        if (left > 0) {
            text.append("; ").append(left).append(" more cells left for another pass");
        }
        if (planned == 0 && skippedTotal == 0 && solidsLeft == 0) {
            summary = "nothing to clear around " + coords(center);
            return TaskStatus.SUCCESS;
        }
        summary = text.toString();
        return total == 0 && skippedTotal > 0 ? TaskStatus.FAILED : TaskStatus.SUCCESS;
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
