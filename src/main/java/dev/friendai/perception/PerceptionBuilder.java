package dev.friendai.perception;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.task.Task;
import dev.friendai.task.combat.Threats;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;

public final class PerceptionBuilder {
    private static final Set<String> IGNORED_BLOCKS =
            Set.of("air", "cave_air", "void_air", "stone", "deepslate", "dirt", "grass_block", "water", "bedrock");
    private static final int BRIEF_RADIUS = 8;
    private static final int DETAILED_RADIUS = 16;
    private static final int ENTITY_RADIUS = 16;
    private static final int TOP = 10;
    private static final double THREAT_RADIUS = 16;
    private static final int MAX_THREATS = 3;

    private PerceptionBuilder() {}

    public static PerceptionSnapshot brief(CompanionEntity c) {
        return build(c, BRIEF_RADIUS, null);
    }

    /** The brief snapshot with the view of {@code principal} (or the owner when null or offline). */
    public static PerceptionSnapshot brief(CompanionEntity c, UUID principal) {
        return build(c, BRIEF_RADIUS, OwnerFocusBuilder.of(c, principal));
    }

    public static PerceptionSnapshot detailed(CompanionEntity c) {
        return build(c, DETAILED_RADIUS, null);
    }

    private static PerceptionSnapshot build(CompanionEntity c, int blockRadius, OwnerFocus focus) {
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
                c.hunger().food(),
                level.isBrightOutside() ? "day" : "night",
                biome,
                weather,
                ownerDistance,
                top(countBlocks(level, pos, blockRadius)),
                top(countEntities(c, level)),
                inventory(c),
                c.equipmentText(),
                task,
                focus,
                selfStatus(c, level, pos),
                threats(c, level),
                LandmarkScanner.scan(level, pos));
    }

    private static List<Threat> threats(CompanionEntity c, ServerLevel level) {
        return Threats.hostilesNear(level, c.position(), THREAT_RADIUS).stream()
                .limit(MAX_THREATS)
                .map(e -> new Threat(
                        shortName(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType())),
                        c.distanceTo(e),
                        RelativeDirection.relativeTo(c.getYRot(), e.getX() - c.getX(), e.getZ() - c.getZ()),
                        e.getHealth(),
                        e.getMaxHealth(),
                        targetOf(c, e)))
                .toList();
    }

    private static String targetOf(CompanionEntity c, LivingEntity entity) {
        if (!(entity instanceof Mob mob)) {
            return "nothing";
        }
        LivingEntity target = mob.getTarget();
        if (target == null) {
            return "nothing";
        }
        if (target == c) {
            return "me";
        }
        ServerPlayer owner = c.owner().orElse(null);
        return owner != null && target == owner ? "owner" : "someone else";
    }

    private static SelfStatus selfStatus(CompanionEntity c, ServerLevel level, BlockPos pos) {
        int headroom = 0;
        while (headroom < SelfStatus.MAX_HEADROOM) {
            BlockPos above = pos.above(headroom + 1);
            if (!level.hasChunkAt(above)
                    || !level.getBlockState(above).getCollisionShape(level, above).isEmpty()) {
                break;
            }
            headroom++;
        }
        return new SelfStatus(c.onGround(), c.isInWater(), c.isInLava(), c.isOnFire(), c.isInWall(),
                level.canSeeSky(pos), level.getMaxLocalRawBrightness(pos), GroundText.under(level, c), headroom);
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
