package dev.friendai.features.construction.minecraft.task;

import dev.friendai.features.navigation.minecraft.nav.Approach;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.inventory.InventoryOps;
import dev.friendai.task.inventory.ItemMatcher;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;

/** Places torches from the inventory in dark spots around where the task started. */
public final class LightAreaTask implements Task {
    public static final int MAX_TORCHES = 16;
    public static final int DARK_BELOW = 8;
    public static final int LIT_DISTANCE = 5;
    public static final int WAIT_TICKS = 5;
    private final int radius;
    private final ItemMatcher torch = ItemMatcher.parse("minecraft:torch").orElseThrow();
    private final List<BlockPos> placed = new ArrayList<>();
    private final Set<BlockPos> skipped = new HashSet<>();
    private BlockPos origin;
    private BlockPos spot;
    private Approach approach;
    private int waitTicks;
    private String summary = "";

    public LightAreaTask(int radius) {
        this.radius = radius;
    }

    @Override
    public String type() {
        return "light_area";
    }

    @Override
    public void start(TaskContext c) {}

    @Override
    public TaskStatus tick(TaskContext c) {
        if (origin == null) {
            origin = c.companion().blockPosition();
        }
        if (waitTicks > 0) {
            waitTicks--;
            return TaskStatus.RUNNING;
        }
        if (approach != null) {
            return act(c);
        }
        Optional<BlockPos> next = findDarkSpot(c);
        if (next.isEmpty()) {
            if (!skipped.isEmpty()) {
                summary = placed.isEmpty()
                        ? "couldn't reach or place any torch within " + radius + " blocks"
                        : "placed " + placed.size() + " torches; skipped " + skipped.size()
                                + " dark spots I couldn't reach or place on";
                return placed.isEmpty() ? TaskStatus.FAILED : TaskStatus.SUCCESS;
            }
            summary = placed.isEmpty()
                    ? "no dark spots within " + radius + " blocks"
                    : "placed " + placed.size() + " torches; no dark spots left within " + radius + " blocks";
            return TaskStatus.SUCCESS;
        }
        if (placed.size() >= MAX_TORCHES) {
            summary = "placed " + MAX_TORCHES + " torches (the maximum for one task)";
            return TaskStatus.SUCCESS;
        }
        if (InventoryOps.count(c.companion().inventory(), torch) == 0) {
            summary = placed.isEmpty()
                    ? "I don't have minecraft:torch"
                    : "placed " + placed.size() + " torches, then ran out of torches";
            return placed.isEmpty() ? TaskStatus.FAILED : TaskStatus.SUCCESS;
        }
        spot = next.get();
        approach = new Approach(spot);
        return TaskStatus.RUNNING;
    }

    private TaskStatus act(TaskContext c) {
        var companion = c.companion();
        switch (approach.tick(c)) {
            case MOVING -> {
                return TaskStatus.RUNNING;
            }
            case UNREACHABLE -> {
                return skip();
            }
            case IN_REACH -> {}
        }
        if (!companion.hands().canInteract(spot)) {
            return skip();
        }
        List<ItemStack> taken = InventoryOps.remove(companion.inventory(), torch, 1);
        if (taken.isEmpty()) {
            approach = null;
            summary = placed.isEmpty()
                    ? "I don't have minecraft:torch"
                    : "placed " + placed.size() + " torches, then ran out of torches";
            return placed.isEmpty() ? TaskStatus.FAILED : TaskStatus.SUCCESS;
        }
        companion.hands().place(taken.get(0), spot.below(), Direction.UP);
        if (c.level().getBlockState(spot).is(Blocks.TORCH)) {
            placed.add(spot);
        } else {
            skipped.add(spot);
        }
        approach = null;
        spot = null;
        waitTicks = WAIT_TICKS;
        return TaskStatus.RUNNING;
    }

    private TaskStatus skip() {
        skipped.add(spot);
        approach = null;
        spot = null;
        return TaskStatus.RUNNING;
    }

    private Optional<BlockPos> findDarkSpot(TaskContext c) {
        BlockPos from = c.companion().blockPosition();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -2; dy <= 2; dy++) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    if (skipped.contains(pos) || pos.equals(from)) {
                        continue;
                    }
                    if (!c.level().getBlockState(pos).isAir()) {
                        continue;
                    }
                    if (!c.level().getBlockState(pos.below()).isFaceSturdy(c.level(), pos.below(), Direction.UP)) {
                        continue;
                    }
                    if (c.level().getBrightness(LightLayer.BLOCK, pos) >= DARK_BELOW) {
                        continue;
                    }
                    if (litByTorch(pos)) {
                        continue;
                    }
                    double distance = from.distSqr(pos);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = pos;
                    }
                }
            }
        }
        return Optional.ofNullable(best);
    }

    private boolean litByTorch(BlockPos pos) {
        for (BlockPos torchPos : placed) {
            int distance = Math.abs(torchPos.getX() - pos.getX())
                    + Math.abs(torchPos.getY() - pos.getY())
                    + Math.abs(torchPos.getZ() - pos.getZ());
            if (distance <= LIT_DISTANCE) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void stop(TaskContext c) {
        if (approach != null) {
            approach.stop(c);
        }
    }

    @Override
    public String describe() {
        return "lighting up the area within " + radius + " blocks";
    }

    @Override
    public String resultSummary() {
        return summary;
    }
}
