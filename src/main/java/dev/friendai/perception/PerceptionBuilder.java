package dev.friendai.perception;

import dev.friendai.core.FriendaiServerState;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.perception.resource.ResourceCandidates;
import dev.friendai.task.TaskSettings;
import dev.friendai.task.combat.Threats;
import dev.friendai.task.mining.BlockMatcher;
import dev.friendai.task.mining.TreeFinder;
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
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public final class PerceptionBuilder {
    private static final Set<String> IGNORED_BLOCKS =
            Set.of("air", "cave_air", "void_air", "stone", "deepslate", "dirt", "grass_block", "water", "bedrock");
    private static final int BRIEF_RADIUS = 8;
    private static final int DETAILED_RADIUS = 16;
    private static final int ENTITY_RADIUS = 16;
    private static final int TOP = 10;
    private static final int MAX_THREATS = 3;
    private static final int MAX_TREES = 3;
    private static final int TREE_DOWN = 4;
    private static final int TREE_UP = 24;

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
        String biome = level.getBiome(pos)
                .unwrapKey()
                .map(k -> k.identifier().getPath())
                .orElse("unknown");
        String weather = level.isThundering() ? "thunder" : level.isRaining() ? "rain" : "clear";
        Double ownerDistance = c.owner()
                .filter(o -> o.level() == level)
                .map(o -> (double) c.distanceTo(o))
                .orElse(null);
        String task = c.taskRunner().queueSummary();
        if (c.ownerUuid() != null) {
            String freeTime = dev.friendai.agent.autonomy.FreeTimeText.stateLine(
                    FriendaiServerState.get(level.getServer()).freeTime().state(c.ownerUuid()),
                    System.currentTimeMillis());
            task = freeTime.isEmpty() ? task : task + "\n" + freeTime;
        }
        ResourceCandidates resources =
                FriendaiServerState.get(level.getServer()).resources();
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
                top(countBlocks(resources, c, blockRadius)),
                top(countEntities(c, level)),
                inventory(c),
                equipmentText(c),
                task,
                focus,
                selfStatus(c, level, pos),
                threats(c, level),
                LandmarkScanner.scan(c, resources),
                trees(c, resources, pos),
                c.experience().level(),
                c.creativeMode());
    }

    private static String equipmentText(CompanionEntity c) {
        String text = c.equipmentText();
        String action = c.combatController().activeAction();
        if (!"none".equals(action)) {
            text += "; doing: " + action + " (" + c.combatController().activeReason() + ")";
        }
        return text;
    }

    /** The nearest tree-like clusters, so the model need not infer a tree from a count of log blocks. */
    private static List<TreeSummary> trees(CompanionEntity c, ResourceCandidates resources, BlockPos pos) {
        if (!TaskSettings.treePerception) {
            return List.of();
        }
        BlockMatcher leaves = BlockMatcher.parse("#minecraft:leaves").orElse(null);
        Map<BlockPos, String> observedLogs =
                resources.visibleBlockIds(c, TaskSettings.treePerceptionRadius).entrySet().stream()
                        .filter(entry -> {
                            var id = Identifier.tryParse(entry.getValue());
                            return id != null
                                    && BuiltInRegistries.BLOCK.containsKey(id)
                                    && BuiltInRegistries.BLOCK
                                            .getValue(id)
                                            .defaultBlockState()
                                            .is(net.minecraft.tags.BlockTags.LOGS);
                        })
                        .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        Set<BlockPos> observedLeaves = leaves == null
                ? Set.of()
                : Set.copyOf(resources.visible(c, leaves, TaskSettings.treePerceptionRadius, 1024));
        return TreeFinder.findObserved((ServerLevel) c.level(), pos, observedLogs, observedLeaves, MAX_TREES).stream()
                .map(TreeFinder.TreeInfo::summary)
                .toList();
    }

    private static List<Threat> threats(CompanionEntity c, ServerLevel level) {
        List<Threat> result = new java.util.ArrayList<>();
        for (LivingEntity entity : Threats.hostilesNear(level, c.position(), 16)) {
            if (result.size() >= MAX_THREATS) break;
            if (level.clip(new ClipContext(
                                    c.getEyePosition(),
                                    entity.getBoundingBox().getCenter(),
                                    ClipContext.Block.COLLIDER,
                                    ClipContext.Fluid.NONE,
                                    c))
                            .getType()
                    != HitResult.Type.MISS) continue;
            result.add(new Threat(
                    shortName(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType())),
                    c.distanceTo(entity),
                    RelativeDirection.relativeTo(c.getYRot(), entity.getX() - c.getX(), entity.getZ() - c.getZ()),
                    entity.getHealth(),
                    entity.getMaxHealth(),
                    targetOf(c, entity)));
        }
        return List.copyOf(result);
    }

    private static String targetOf(CompanionEntity c, LivingEntity entity) {
        if (!(entity instanceof Mob mob) || mob.getTarget() == null) return "nothing";
        if (mob.getTarget() == c) return "me";
        ServerPlayer owner = c.owner().orElse(null);
        return owner != null && mob.getTarget() == owner ? "owner" : "someone else";
    }

    private static SelfStatus selfStatus(CompanionEntity c, ServerLevel level, BlockPos pos) {
        int headroom = 0;
        while (headroom < SelfStatus.MAX_HEADROOM) {
            BlockPos above = pos.above(headroom + 1);
            if (!level.hasChunkAt(above)
                    || !level.getBlockState(above)
                            .getCollisionShape(level, above)
                            .isEmpty()) {
                break;
            }
            headroom++;
        }
        return new SelfStatus(
                c.onGround(),
                c.isInWater(),
                c.isInLava(),
                c.isOnFire(),
                c.isInWall(),
                level.canSeeSky(pos),
                level.getMaxLocalRawBrightness(pos),
                GroundText.under(level, c),
                headroom);
    }

    private static Map<String, Integer> countBlocks(
            ResourceCandidates resources, CompanionEntity companion, int radius) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (var observation : resources.confirmedRecent(companion, radius)) {
            Identifier id = Identifier.tryParse(observation.blockId());
            if (id == null) continue;
            String name = shortName(id);
            if (!IGNORED_BLOCKS.contains(name)) counts.merge(name, 1, Integer::sum);
        }
        return counts;
    }

    private static Map<String, Integer> countEntities(CompanionEntity c, ServerLevel level) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        List<Entity> nearby = level.getEntities(c, c.getBoundingBox().inflate(ENTITY_RADIUS));
        for (Entity e : nearby) {
            Vec3 eye = c.getEyePosition();
            Vec3 target = e.getBoundingBox().getCenter();
            if (eye.distanceToSqr(target) > ENTITY_RADIUS * ENTITY_RADIUS
                    || level.clip(new ClipContext(eye, target, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, c))
                                    .getType()
                            != HitResult.Type.MISS) continue;
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
                .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(TOP)
                .forEach(e -> sorted.put(e.getKey(), e.getValue()));
        return sorted;
    }

    private static String shortName(Identifier id) {
        return id.getNamespace().equals("minecraft") ? id.getPath() : id.toString();
    }
}
