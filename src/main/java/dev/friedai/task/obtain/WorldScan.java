package dev.friedai.task.obtain;

import dev.friedai.crafting.obtain.WorldFacts;
import dev.friedai.entity.CompanionEntity;
import dev.friedai.task.crafting.Stations;
import dev.friedai.task.farming.Crops;
import dev.friedai.task.interact.ContainerAccess;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;

/** One look at the surroundings, in the shape {@link dev.friedai.crafting.obtain.ObtainPlanner} consumes. */
public final class WorldScan {
    /** How far blocks are counted. */
    public static final int BLOCK_RADIUS = 32;
    /** How far blocks are counted vertically. */
    public static final int BLOCK_VERTICAL = 16;
    /** How far mobs are counted. */
    public static final int MOB_RADIUS = 24;
    /** How far containers and crops are counted. */
    public static final int CONTAINER_RADIUS = 16;
    public static final int CROP_RADIUS = 16;

    private WorldScan() {}

    public static WorldFacts scan(
            ServerLevel level, CompanionEntity companion, int radius, boolean containers, boolean explore) {
        BlockPos origin = companion.blockPosition();
        Map<String, Integer> blocks = countBlocks(level, origin, radius);
        Map<String, Integer> mobs = countMobs(level, companion, origin, MOB_RADIUS);
        Map<String, Integer> containerItems = containers ? countContainers(level, origin, CONTAINER_RADIUS) : Map.of();
        Map<String, Integer> crops = countCrops(level, origin, CROP_RADIUS);
        Set<String> stations = new LinkedHashSet<>();
        for (var station : Stations.available(level, companion)) {
            if (station != dev.friedai.crafting.Station.INVENTORY) {
                stations.add(station.noun());
            }
        }
        boolean overworld = Level.OVERWORLD.equals(level.dimension());
        return new WorldFacts(blocks, mobs, containerItems, crops, stations, explore, overworld);
    }

    private static Map<String, Integer> countBlocks(ServerLevel level, BlockPos origin, int radius) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        int vertical = Math.min(radius, BLOCK_VERTICAL);
        int minY = Math.max(level.getMinY(), origin.getY() - vertical);
        int maxY = Math.min(level.getMaxY(), origin.getY() + vertical);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = origin.getX() - radius; x <= origin.getX() + radius; x++) {
            for (int z = origin.getZ() - radius; z <= origin.getZ() + radius; z++) {
                if (!level.hasChunkAt(x, z)) {
                    continue;
                }
                for (int y = minY; y <= maxY; y++) {
                    pos.set(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (state.isAir() || state.liquid()) {
                        continue;
                    }
                    counts.merge(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(), 1, Integer::sum);
                }
            }
        }
        return counts;
    }

    private static Map<String, Integer> countMobs(
            ServerLevel level, CompanionEntity companion, BlockPos origin, int radius) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        AABB box = new AABB(origin).inflate(radius);
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, box)) {
            if (entity == companion || entity instanceof Player) {
                continue;
            }
            if (!companion.hands().canInteract(entity.blockPosition())) {
                continue;
            }
            counts.merge(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(), 1, Integer::sum);
        }
        return counts;
    }

    private static Map<String, Integer> countContainers(ServerLevel level, BlockPos origin, int radius) {
        Map<String, Integer> items = new LinkedHashMap<>();
        forEachBlockEntity(level, origin, radius, (pos, entity) -> ContainerAccess.at(level, pos).ifPresent(container -> {
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stack = container.getItem(slot);
                if (!stack.isEmpty()) {
                    items.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(), Integer::sum);
                }
            }
        }));
        return items;
    }

    private static Map<String, Integer> countCrops(ServerLevel level, BlockPos origin, int radius) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        int vertical = Math.min(radius, BLOCK_VERTICAL);
        int minY = Math.max(level.getMinY(), origin.getY() - vertical);
        int maxY = Math.min(level.getMaxY(), origin.getY() + vertical);
        for (int x = origin.getX() - radius; x <= origin.getX() + radius; x++) {
            for (int z = origin.getZ() - radius; z <= origin.getZ() + radius; z++) {
                if (!level.hasChunkAt(x, z)) {
                    continue;
                }
                for (int y = minY; y <= maxY; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (Crops.harvestable(level, pos).isPresent()) {
                        counts.merge(BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString(),
                                1, Integer::sum);
                    }
                }
            }
        }
        return counts;
    }

    private static void forEachBlockEntity(
            ServerLevel level, BlockPos origin, int radius, BlockEntityConsumer consumer) {
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
                for (Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
                    if (origin.distSqr(entry.getKey()) <= (double) radius * radius) {
                        consumer.accept(entry.getKey(), entry.getValue());
                    }
                }
            }
        }
    }

    @FunctionalInterface
    private interface BlockEntityConsumer {
        void accept(BlockPos pos, BlockEntity entity);
    }
}
