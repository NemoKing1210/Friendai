package dev.friendai.task.interact;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

/** Storage containers the companion may use: chests (double chests merged), barrels, shulker boxes. */
public final class ContainerAccess {
    private ContainerAccess() {}

    /** The storage container at pos, or empty for no container, furnaces and ender chests. */
    public static Optional<Container> at(ServerLevel level, BlockPos pos) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (!(blockEntity instanceof Container)) {
            return Optional.empty();
        }
        Container container = HopperBlockEntity.getContainerAt(level, pos);
        if (container == null || container instanceof AbstractFurnaceBlockEntity) {
            return Optional.empty();
        }
        return Optional.of(container);
    }

    /** The nearest position within radius whose block is a storage container. */
    public static Optional<BlockPos> nearest(ServerLevel level, BlockPos origin, int radius) {
        return nearest(level, origin, radius, container -> true);
    }

    /** The nearest position within radius whose container passes the filter. */
    public static Optional<BlockPos> nearest(
            ServerLevel level, BlockPos origin, int radius, Predicate<Container> filter) {
        List<BlockPos> found = all(level, origin, radius, filter);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    /** Every storage container within radius, nearest first. */
    public static List<BlockPos> all(ServerLevel level, BlockPos origin, int radius) {
        return all(level, origin, radius, container -> true);
    }

    /** Every storage container within radius whose contents pass the filter, nearest first. */
    public static List<BlockPos> all(
            ServerLevel level, BlockPos origin, int radius, Predicate<Container> filter) {
        List<BlockPos> found = new ArrayList<>();
        int minChunkX = (origin.getX() - radius) >> 4;
        int maxChunkX = (origin.getX() + radius) >> 4;
        int minChunkZ = (origin.getZ() - radius) >> 4;
        int maxChunkZ = (origin.getZ() + radius) >> 4;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                BlockPos sample = new BlockPos(chunkX << 4, origin.getY(), chunkZ << 4);
                if (!level.hasChunkAt(sample)) {
                    continue;
                }
                LevelChunk chunk = level.getChunkAt(sample);
                for (BlockPos pos : chunk.getBlockEntities().keySet()) {
                    if (origin.distSqr(pos) > (double) radius * radius) {
                        continue;
                    }
                    if (at(level, pos).filter(filter).isPresent()) {
                        found.add(pos.immutable());
                    }
                }
            }
        }
        found.sort(Comparator.<BlockPos>comparingDouble(origin::distSqr)
                .thenComparingInt(BlockPos::getX)
                .thenComparingInt(BlockPos::getY)
                .thenComparingInt(BlockPos::getZ));
        return found;
    }

    /** "minecraft:chest" for summaries. */
    public static String label(ServerLevel level, BlockPos pos) {
        return BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString();
    }

    /** The block id's path ("chest", "barrel"), for prose in summaries. */
    public static String shortLabel(ServerLevel level, BlockPos pos) {
        return BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).getPath();
    }

    /** Inserts as much of the stack as fits, with vanilla rules; returns the leftover. */
    public static ItemStack insert(Container to, ItemStack stack) {
        return HopperBlockEntity.addItem(null, to, stack, null);
    }
}
