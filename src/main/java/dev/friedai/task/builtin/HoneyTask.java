package dev.friedai.task.builtin;

import dev.friedai.task.Task;
import dev.friedai.task.TaskContext;
import dev.friedai.task.TaskStatus;
import dev.friedai.task.nav.Approach;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BeehiveBlock;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.entity.BeehiveBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.Nullable;

/** Collects honey from a smoked, full beehive with a bottle or shears. */
public final class HoneyTask implements Task {
    private static final int RADIUS = 16;

    private final boolean bottle;
    private final @Nullable BlockPos hive;

    private boolean preflightDone;
    private boolean failed;
    private boolean finished;
    private String summary = "";
    private BlockPos target;
    private Approach approach;

    public HoneyTask(boolean bottle, @Nullable BlockPos hive) {
        this.bottle = bottle;
        this.hive = hive;
    }

    @Override
    public String type() {
        return "harvest_honey";
    }

    @Override
    public void start(TaskContext c) {}

    @Override
    public TaskStatus tick(TaskContext c) {
        if (failed) {
            return TaskStatus.FAILED;
        }
        if (finished) {
            return TaskStatus.SUCCESS;
        }
        if (!preflightDone) {
            preflightDone = true;
            Item tool = bottle ? Items.GLASS_BOTTLE : Items.SHEARS;
            if (!carried(c.companion(), tool)) {
                summary = "I have no " + BuiltInRegistries.ITEM.getKey(tool);
                failed = true;
                return TaskStatus.FAILED;
            }
            Optional<BlockPos> found = hive != null ? Optional.of(hive) : nearestHive(c.level(), c.companion().blockPosition());
            if (found.isEmpty()) {
                summary = "no beehive nearby";
                failed = true;
                return TaskStatus.FAILED;
            }
            target = found.get();
            if (!(c.level().getBlockState(target).getBlock() instanceof BeehiveBlock)) {
                summary = "there is no beehive at " + coords(target);
                failed = true;
                return TaskStatus.FAILED;
            }
            if (c.level().getBlockState(target).getValue(BeehiveBlock.HONEY_LEVEL) < BeehiveBlock.MAX_HONEY_LEVELS) {
                summary = "the hive at " + coords(target) + " isn't full yet";
                failed = true;
                return TaskStatus.FAILED;
            }
            if (!CampfireBlock.isSmokeyPos(c.level(), target) && !beesEmpty(c.level(), target)) {
                summary = "the hive at " + coords(target) + " isn't smoked; place a campfire under it";
                failed = true;
                return TaskStatus.FAILED;
            }
        }
        if (approach == null) {
            approach = new Approach(target);
        }
        Approach.State state = approach.tick(c.companion());
        if (state == Approach.State.MOVING) {
            return TaskStatus.RUNNING;
        }
        if (state == Approach.State.UNREACHABLE) {
            summary = "can't reach the hive at " + coords(target);
            failed = true;
            return TaskStatus.FAILED;
        }
        if (!c.companion().hands().canInteract(target)) {
            summary = "the hive at " + coords(target) + " is protected";
            failed = true;
            return TaskStatus.FAILED;
        }
        Item tool = bottle ? Items.GLASS_BOTTLE : Items.SHEARS;
        ItemStack stack = takeOne(c.companion(), tool);
        if (stack.isEmpty()) {
            summary = "I have no " + BuiltInRegistries.ITEM.getKey(tool);
            failed = true;
            return TaskStatus.FAILED;
        }
        int before = c.companion().inventory().countItem(bottle ? Items.HONEY_BOTTLE : Items.HONEYCOMB);
        c.companion().hands().place(stack, target, Direction.UP);
        c.companion().collectNearbyItems(4.0);
        int gained = c.companion().inventory().countItem(bottle ? Items.HONEY_BOTTLE : Items.HONEYCOMB) - before;
        if (gained <= 0) {
            summary = "the hive at " + coords(target) + " didn't give any honey";
            failed = true;
            return TaskStatus.FAILED;
        }
        Item got = bottle ? Items.HONEY_BOTTLE : Items.HONEYCOMB;
        summary = "got " + gained + " " + BuiltInRegistries.ITEM.getKey(got) + " from the hive at " + coords(target);
        finished = true;
        return TaskStatus.SUCCESS;
    }

    @Override
    public void stop(TaskContext c) {
        c.companion().getNavigation().stop();
    }

    @Override
    public String describe() {
        return "harvesting honey";
    }

    @Override
    public String resultSummary() {
        return summary;
    }

    private static boolean carried(dev.friedai.entity.CompanionEntity companion, Item item) {
        for (int slot = 0; slot < companion.inventory().getContainerSize(); slot++) {
            if (companion.inventory().getItem(slot).is(item)) {
                return true;
            }
        }
        return false;
    }

    private static ItemStack takeOne(dev.friedai.entity.CompanionEntity companion, Item item) {
        for (int slot = 0; slot < companion.inventory().getContainerSize(); slot++) {
            ItemStack stack = companion.inventory().getItem(slot);
            if (stack.is(item)) {
                ItemStack one = stack.copyWithCount(1);
                stack.shrink(1);
                if (stack.isEmpty()) {
                    companion.inventory().setItem(slot, ItemStack.EMPTY);
                }
                companion.inventory().setChanged();
                return one;
            }
        }
        return ItemStack.EMPTY;
    }

    private static Optional<BlockPos> nearestHive(ServerLevel level, BlockPos origin) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        int minChunkX = (origin.getX() - RADIUS) >> 4;
        int maxChunkX = (origin.getX() + RADIUS) >> 4;
        int minChunkZ = (origin.getZ() - RADIUS) >> 4;
        int maxChunkZ = (origin.getZ() + RADIUS) >> 4;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                BlockPos sample = new BlockPos(chunkX << 4, origin.getY(), chunkZ << 4);
                if (!level.hasChunkAt(sample)) {
                    continue;
                }
                LevelChunk chunk = level.getChunkAt(sample);
                for (BlockPos pos : chunk.getBlockEntities().keySet()) {
                    if (origin.distSqr(pos) > (double) RADIUS * RADIUS) {
                        continue;
                    }
                    if (!(level.getBlockState(pos).getBlock() instanceof BeehiveBlock)) {
                        continue;
                    }
                    double distance = pos.distSqr(origin);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = pos.immutable();
                    }
                }
            }
        }
        return Optional.ofNullable(best);
    }

    private static boolean beesEmpty(ServerLevel level, BlockPos pos) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        return blockEntity instanceof BeehiveBlockEntity beehive && beehive.isEmpty();
    }

    private static String coords(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }
}
