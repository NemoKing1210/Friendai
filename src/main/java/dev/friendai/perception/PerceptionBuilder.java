package dev.friendai.perception;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.task.Task;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

public final class PerceptionBuilder {
    private static final Set<String> IGNORED_BLOCKS =
            Set.of("air", "cave_air", "void_air", "stone", "deepslate", "dirt", "grass_block", "water", "bedrock");
    private static final int BRIEF_RADIUS = 8;
    private static final int DETAILED_RADIUS = 16;
    private static final int ENTITY_RADIUS = 16;
    private static final int TOP = 10;

    private PerceptionBuilder() {}

    public static PerceptionSnapshot brief(CompanionEntity c) {
        return build(c, BRIEF_RADIUS);
    }

    public static PerceptionSnapshot detailed(CompanionEntity c) {
        return build(c, DETAILED_RADIUS);
    }

    private static PerceptionSnapshot build(CompanionEntity c, int blockRadius) {
        ServerLevel level = (ServerLevel) c.level();
        BlockPos pos = c.blockPosition();
        String biome = level.getBiome(pos).unwrapKey().map(k -> k.identifier().getPath()).orElse("unknown");
        String weather = level.isThundering() ? "thunder" : level.isRaining() ? "rain" : "clear";
        Double ownerDistance = c.owner().filter(o -> o.level() == level).map(o -> (double) c.distanceTo(o)).orElse(null);
        String task = c.taskRunner().current().map(Task::describe).orElse("none");
        return new PerceptionSnapshot(
                pos.getX() + " " + pos.getY() + " " + pos.getZ(),
                level.dimension().identifier().getPath(),
                c.getHealth(),
                c.getMaxHealth(),
                level.isBrightOutside() ? "day" : "night",
                biome,
                weather,
                ownerDistance,
                top(countBlocks(level, pos, blockRadius)),
                top(countEntities(c, level)),
                inventory(c),
                c.equipmentText(),
                task);
    }

    private static Map<String, Integer> countBlocks(ServerLevel level, BlockPos center, int radius) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = center.getX() - radius; x <= center.getX() + radius; x++) {
            for (int z = center.getZ() - radius; z <= center.getZ() + radius; z++) {
                if (!level.hasChunkAt(x, z)) {
                    continue;
                }
                for (int y = Math.max(level.getMinY(), center.getY() - radius); y <= Math.min(level.getMaxY(), center.getY() + radius); y++) {
                    pos.set(x, y, z);
                    String name = shortName(BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()));
                    if (!IGNORED_BLOCKS.contains(name)) {
                        counts.merge(name, 1, Integer::sum);
                    }
                }
            }
        }
        return counts;
    }

    private static Map<String, Integer> countEntities(CompanionEntity c, ServerLevel level) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        List<Entity> nearby = level.getEntities(c, c.getBoundingBox().inflate(ENTITY_RADIUS));
        for (Entity e : nearby) {
            counts.merge(shortName(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType())), 1, Integer::sum);
        }
        return counts;
    }

    private static Map<String, Integer> inventory(CompanionEntity c) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (int i = 0; i < c.inventory().getContainerSize(); i++) {
            ItemStack stack = c.inventory().getItem(i);
            if (!stack.isEmpty()) {
                counts.merge(shortName(BuiltInRegistries.ITEM.getKey(stack.getItem())), stack.getCount(), Integer::sum);
            }
        }
        return top(counts);
    }

    private static Map<String, Integer> top(Map<String, Integer> counts) {
        Map<String, Integer> sorted = new LinkedHashMap<>();
        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()).thenComparing(Map.Entry.comparingByKey()))
                .limit(TOP)
                .forEach(e -> sorted.put(e.getKey(), e.getValue()));
        return sorted;
    }

    private static String shortName(Identifier id) {
        return id.getNamespace().equals("minecraft") ? id.getPath() : id.toString();
    }
}
