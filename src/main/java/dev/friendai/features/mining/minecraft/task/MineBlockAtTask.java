package dev.friendai.features.mining.minecraft.task;

import dev.friendai.common.Components;
import dev.friendai.debug.DebugAction;
import dev.friendai.debug.DebugTarget;
import dev.friendai.features.construction.minecraft.build.ScaffoldCleanupTask;
import dev.friendai.features.mining.ClusterFill;
import dev.friendai.features.mining.MineReport;
import dev.friendai.features.mining.minecraft.mining.BlockBreaker;
import dev.friendai.features.mining.minecraft.mining.CatchPlatform;
import dev.friendai.features.mining.minecraft.mining.DropGuard;
import dev.friendai.features.mining.minecraft.mining.DropRoom;
import dev.friendai.features.mining.minecraft.mining.MiningRefusal;
import dev.friendai.features.mining.minecraft.mining.TemporaryBlockCleanup;
import dev.friendai.features.navigation.minecraft.nav.PlacementLedger;
import dev.friendai.features.navigation.pathing.Pos;
import dev.friendai.foundation.text.UserMessage;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.inventory.DropCollector;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Mines one named block, continues into the vein it belongs to, and reports only the drops it actually took.
 *
 * <p>The vein is the six-connected cluster of the same block when that block is an ore or a log, capped at
 * {@link #MAX_VEIN_BLOCKS} and broken one cell at a time through the ordinary breaker, so protection, support, tool and
 * reach checks apply to every cell exactly as they do to the pointed one. A neighbour that is refused or cannot be
 * reached stops the expansion cleanly and is named in the summary.
 */
public final class MineBlockAtTask implements Task {
    /** How far from the block the sweep for its drop looks; the drop lands there or close by. */
    private static final int SWEEP_RADIUS = 6;
    /** The most blocks a pointed vein may take, the pointed block included. */
    private static final int MAX_VEIN_BLOCKS = 32;
    /** The largest sweep a vein's spread can ask for. */
    private static final int MAX_SWEEP_RADIUS = 24;

    private final BlockPos pos;
    private BlockBreaker breaker;
    private DropCollector sweep;
    private BlockState startBlock;
    private String summary = "";
    /** Whether the safe break of the block under the companion was already tried. */
    private boolean supportRetry;
    /** The vein still to break, in the order the fill found it. */
    private final Deque<BlockPos> vein = new ArrayDeque<>();

    /** The catch blocks this job placed, so they are taken down with it. */
    private final PlacementLedger placements = new PlacementLedger();
    /** The platform under the pointed block while it is being built, or null. */
    private CatchPlatform platform;

    private TemporaryBlockCleanup catchCleanup;
    private TaskStatus pendingStatus;
    /** The platform's own note for the summary: placed and removed, or left behind. */
    private String catchNote = "";

    private final List<ItemStack> receipts = new ArrayList<>();
    private final List<DropCollector.Leftover> leftovers = new ArrayList<>();
    private boolean sawIntended;
    private boolean expanded;
    private int mined;
    /** How far the farthest broken cell lies from the pointed one, so one sweep covers the vein. */
    private double spread;
    /** Why the vein stopped early, in the summary's words, or empty. */
    private String stop = "";

    public MineBlockAtTask(BlockPos pos) {
        this.pos = pos;
    }

    @Override
    public String type() {
        return "mine_block_at";
    }

    @Override
    public List<DebugTarget> debugTargets() {
        MiningRefusal refusal = breaker == null ? null : breaker.refusal();
        List<DebugTarget> targets = new ArrayList<>();
        targets.add(new DebugTarget(
                pos.getX(), pos.getY(), pos.getZ(), DebugAction.BREAK, refusal == null ? "" : refusal.describe()));
        for (BlockPos cell : vein) {
            targets.add(new DebugTarget(cell.getX(), cell.getY(), cell.getZ(), DebugAction.BREAK, ""));
        }
        if (platform != null) {
            for (BlockPos cell : platform.cells()) {
                targets.add(new DebugTarget(cell.getX(), cell.getY(), cell.getZ(), DebugAction.PLACE, "catch"));
            }
        }
        return targets;
    }

    @Override
    public void start(TaskContext c) {
        startBlock = c.level().getBlockState(pos);
    }

    @Override
    public TaskStatus tick(TaskContext c) {
        if (catchCleanup != null) {
            TemporaryBlockCleanup.State state = catchCleanup.tick(c);
            if (state == TemporaryBlockCleanup.State.WORKING) {
                return TaskStatus.RUNNING;
            }
            TemporaryBlockCleanup finished = catchCleanup;
            catchCleanup = null;
            catchNote = state == TemporaryBlockCleanup.State.DONE
                    ? "; placed " + placements.size() + " catch block(s) and removed them"
                    : "; left " + finished.left() + " catch block(s) (" + finished.failure() + ")";
            if (finished.kept() > 0) {
                catchNote += "; kept " + finished.kept() + " catch block(s) under the companion";
            }
            summary += catchNote;
            TaskStatus status = pendingStatus;
            pendingStatus = null;
            placements.clear();
            return status;
        }
        TaskStatus status = tickInner(c);
        if (status != TaskStatus.RUNNING && !placements.isEmpty() && catchCleanup == null) {
            catchCleanup = new TemporaryBlockCleanup(placements);
            pendingStatus = status;
            return TaskStatus.RUNNING;
        }
        return status;
    }

    private TaskStatus tickInner(TaskContext c) {
        if (platform != null) {
            CatchPlatform.State state = platform.tick(c);
            if (state == CatchPlatform.State.WORKING) {
                return TaskStatus.RUNNING;
            }
            CatchPlatform finished = platform;
            platform = null;
            if (state == CatchPlatform.State.REFUSED) {
                summary = blockedDrop(finished) + "; " + finished.failure();
                return TaskStatus.FAILED;
            }
        }
        if (sweep != null) {
            if (sweep.tick(c) != DropCollector.State.DONE) {
                return TaskStatus.RUNNING;
            }
            receipts.addAll(sweep.collectedStacks());
            leftovers.addAll(sweep.leftovers());
            sawIntended |= sweep.sawIntended();
            sweep = null;
            return report();
        }
        if (breaker != null) {
            return tickBreaker(c);
        }
        if (mined == 0) {
            return begin(c);
        }
        return next(c);
    }

    private TaskStatus begin(TaskContext c) {
        if (startBlock.isAir()) {
            summary = "there is no block at " + coords();
            return TaskStatus.FAILED;
        }
        if (!c.companion().creativeMode() && startBlock.getDestroySpeed(c.level(), pos) < 0) {
            summary = blockId() + " at " + coords() + " can't be broken";
            return TaskStatus.FAILED;
        }
        if (!c.companion().creativeMode() && !DropRoom.hasRoom(c, pos)) {
            summary = MineReport.notMined(blockId(), pos.getX(), pos.getY(), pos.getZ(), "the inventory is full");
            noticeInventoryFull(c);
            return TaskStatus.FAILED;
        }
        DropGuard.Plan plan = DropGuard.plan(c, pos, placements);
        if (plan.refusal() != null) {
            summary = plan.refusal().describe() + "; " + plan.refusal().remedy();
            return TaskStatus.FAILED;
        }
        platform = plan.platform();
        breaker = new BlockBreaker(pos, this::stillTarget);
        return TaskStatus.RUNNING;
    }

    /** The refusal sentence for a platform that could not be built, naming the block and the reason. */
    private String blockedDrop(CatchPlatform finished) {
        return blockId() + " at " + coords() + " would lose its drop and no catch block could be placed";
    }

    private TaskStatus tickBreaker(TaskContext c) {
        BlockBreaker.State state = breaker.tick(c);
        switch (state) {
            case WORKING -> {
                return TaskStatus.RUNNING;
            }
            case BROKEN -> {
                BlockPos broken = breaker.target() == null ? pos : breaker.target();
                breaker = null;
                onBroken(broken, c);
                return TaskStatus.RUNNING;
            }
            case GONE -> {
                breaker = null;
                if (mined == 0) {
                    summary = "the block at " + coords() + " changed before I could mine it";
                    return TaskStatus.FAILED;
                }
                // A vein cell someone else took is simply skipped; the rest of the vein is still there.
                return next(c);
            }
            case PROTECTED -> {
                MiningRefusal refusal = breaker.refusal();
                if (mined == 0 && refusal instanceof MiningRefusal.OwnSupport own && own.safeDrop() && !supportRetry) {
                    // The caller named this exact block, and the drop is safe: step off it by breaking it.
                    supportRetry = true;
                    breaker.stop(c);
                    breaker = new BlockBreaker(pos, this::stillTarget, true, true);
                    return TaskStatus.RUNNING;
                }
                return stopped(c, state, refusal);
            }
            case UNREACHABLE, OUT_OF_SIGHT -> {
                return stopped(c, state, breaker.refusal());
            }
        }
        return TaskStatus.RUNNING;
    }

    /** Starts the next vein cell, or the drop sweep once the vein is done. */
    private TaskStatus next(TaskContext c) {
        if (!vein.isEmpty()) {
            breaker = new BlockBreaker(vein.poll(), this::stillTarget);
            return TaskStatus.RUNNING;
        }
        if (sweep == null) {
            sweep = new DropCollector(Vec3.atCenterOf(pos), sweepRadius());
            return TaskStatus.RUNNING;
        }
        return report();
    }

    private void onBroken(BlockPos broken, TaskContext c) {
        mined++;
        spread = Math.max(spread, Math.sqrt(Vec3.atCenterOf(broken).distanceToSqr(Vec3.atCenterOf(pos))));
        if (!expanded) {
            expanded = true;
            expandVein(broken, c);
        }
    }

    /**
     * Queues the six-connected cluster of the pointed block when it is an ore or a log. Every queued cell is broken by
     * the same breaker as the pointed one, so nothing bypasses the permission and tool checks.
     */
    private void expandVein(BlockPos broken, TaskContext c) {
        if (!startBlock.is(BlockTags.ORES) && !startBlock.is(BlockTags.LOGS)) {
            return;
        }
        List<Pos> seeds = new ArrayList<>(6);
        for (Direction side : Direction.values()) {
            BlockPos neighbour = broken.relative(side);
            if (c.level().isLoaded(neighbour) && stillTarget(c.level().getBlockState(neighbour))) {
                seeds.add(new Pos(neighbour.getX(), neighbour.getY(), neighbour.getZ()));
            }
        }
        if (seeds.isEmpty()) {
            return;
        }
        List<Pos> cluster = ClusterFill.expand(
                seeds,
                (x, y, z) -> {
                    BlockPos cell = new BlockPos(x, y, z);
                    return c.level().isLoaded(cell) && stillTarget(c.level().getBlockState(cell));
                },
                MAX_VEIN_BLOCKS - 1);
        for (Pos cell : cluster) {
            vein.add(new BlockPos(cell.x(), cell.y(), cell.z()));
        }
    }

    /** A refusal: on the pointed block it fails the job; on a vein cell it stops the vein and reports the cause. */
    private TaskStatus stopped(TaskContext c, BlockBreaker.State state, MiningRefusal refusal) {
        BlockPos target = breaker.target();
        breaker = null;
        if (mined == 0) {
            summary = switch (state) {
                case UNREACHABLE ->
                    refusal != null ? refusal.describe() + "; " + refusal.remedy() : "can't reach " + coords();
                case OUT_OF_SIGHT ->
                    refusal != null
                            ? refusal.describe() + "; " + refusal.remedy()
                            : coords() + " is hidden behind another block";
                default -> refusal != null ? refusal.describe() + "; " + refusal.remedy() : coords() + " is protected";
            };
            return TaskStatus.FAILED;
        }
        String where = target == null ? coords() : coords(target);
        stop = refusal != null
                ? "stopped at a neighbour at " + where + ": " + refusal.describe()
                : "stopped at a neighbour I could not reach at " + where;
        vein.clear();
        return next(c);
    }

    private TaskStatus report() {
        Map<String, Integer> taken = new LinkedHashMap<>();
        for (ItemStack stack : receipts) {
            if (!stack.isEmpty()) {
                taken.merge(itemId(stack), stack.getCount(), Integer::sum);
            }
        }
        List<MineReport.Taken> takenList = new ArrayList<>(taken.size());
        taken.forEach((item, count) -> takenList.add(new MineReport.Taken(item, count)));
        List<MineReport.Untaken> left = new ArrayList<>(leftovers.size());
        for (DropCollector.Leftover leftover : leftovers) {
            BlockPos cell = leftover.cell();
            left.add(new MineReport.Untaken(
                    itemId(leftover.stack()), leftover.stack().getCount(), cell.getX(), cell.getY(), cell.getZ()));
        }
        summary = MineReport.success(
                blockId(), mined, pos.getX(), pos.getY(), pos.getZ(), takenList, left, sawIntended, stop);
        return TaskStatus.SUCCESS;
    }

    private int sweepRadius() {
        return (int) Math.min(MAX_SWEEP_RADIUS, Math.ceil(SWEEP_RADIUS + spread));
    }

    private boolean stillTarget(BlockState state) {
        return state.is(startBlock.getBlock());
    }

    private void noticeInventoryFull(TaskContext c) {
        c.owner()
                .ifPresent(player -> player.sendSystemMessage(
                        Components.of(UserMessage.of("friendai.mining.inventory_full")), true));
    }

    @Override
    public void stop(TaskContext c) {
        if (platform != null) {
            // The cells already placed stay in the ledger and in the placed service, so they are never unrecorded.
            platform.stop();
            platform = null;
        }
        if (catchCleanup != null) {
            catchCleanup.stop(c);
            catchCleanup = null;
        }
        if (sweep != null) {
            sweep.stop(c);
            sweep = null;
        }
        if (breaker != null) {
            breaker.stop(c);
        }
        vein.clear();
    }

    /** A cancelled job takes its catch blocks down like a cancelled build takes its scaffold. */
    @Override
    public Task cleanupAfterCancel(TaskContext c) {
        return placements.isEmpty() ? null : new ScaffoldCleanupTask(placements.positions());
    }

    @Override
    public String describe() {
        return "mining the block at " + coords();
    }

    @Override
    public String resultSummary() {
        return summary;
    }

    private String coords() {
        return coords(pos);
    }

    private static String coords(BlockPos at) {
        return at.getX() + " " + at.getY() + " " + at.getZ();
    }

    private static String itemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private String blockId() {
        return BuiltInRegistries.BLOCK.getKey(startBlock.getBlock()).toString();
    }
}
