package dev.friendai.task.obtain;

import dev.friendai.crafting.Station;
import dev.friendai.crafting.obtain.WorldFacts;
import dev.friendai.core.FriendaiServerState;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.knowledge.ResourceKnowledge;
import dev.friendai.perception.resource.ResourceCandidates;
import dev.friendai.task.crafting.Stations;
import dev.friendai.task.farming.Crops;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** A bounded snapshot of directly observed surroundings for the obtain planner. */
public final class WorldScan {
    public static final int BLOCK_RADIUS = 64;
    public static final int BLOCK_VERTICAL = 16;
    public static final int MOB_RADIUS = 24;
    public static final int CONTAINER_RADIUS = 16;
    public static final int CROP_RADIUS = 16;

    private WorldScan() {}

    public static WorldFacts scan(ServerLevel level, CompanionEntity companion, int radius, boolean containers, boolean explore) {
        return scan(level, companion, radius, containers, explore, Set.of(), Set.of());
    }

    public static WorldFacts scan(ServerLevel level, CompanionEntity companion, int radius, boolean containers,
            boolean explore, Set<Long> verifiedSiteIds, Set<Long> excludedSiteIds) {
        ResourceCandidates evidence = FriendaiServerState.get(level.getServer()).resources();
        Map<String, Integer> blocks = new LinkedHashMap<>();
        for (var observation : evidence.currentVisible(companion, radius)) blocks.merge(observation.blockId(), 1, Integer::sum);
        Map<String, Integer> mobs = countVisibleMobs(level, companion, MOB_RADIUS);
        Map<String, Integer> crops = countVisibleCrops(level, companion, evidence);
        Map<String, Integer> containerItems = containers ? countKnownContainerItems(level, companion) : Map.of();
        Set<String> stations = new LinkedHashSet<>();
        for (Station station : Stations.available(level, companion)) {
            if (station != Station.INVENTORY) stations.add(station.noun());
        }
        return new WorldFacts(blocks, mobs, containerItems, crops, stations, explore,
                Level.OVERWORLD.equals(level.dimension()), explore ? evidence.plannerCandidates(companion, 20) : java.util.List.of(),
                verifiedSiteIds, excludedSiteIds);
    }

    private static Map<String, Integer> countKnownContainerItems(ServerLevel level, CompanionEntity companion) {
        if (companion.ownerUuid() == null) return Map.of();
        Map<String, Integer> counts = new LinkedHashMap<>();
        var book = FriendaiServerState.get(level.getServer()).knowledge().book(companion.ownerUuid());
        String dimension = level.dimension().identifier().toString();
        BlockPos origin = companion.blockPosition();
        long now = level.getServer().overworld().getGameTime();
        for (ResourceKnowledge.ContainerObservation observation : book.containerObservations()) {
            var location = observation.location();
            if (!dimension.equals(location.dimension()) || now - observation.observedTick() > 24_000) continue;
            var pos = location.pos();
            double dx = pos.x() - origin.getX();
            double dy = pos.y() - origin.getY();
            double dz = pos.z() - origin.getZ();
            if (dx * dx + dy * dy + dz * dz > (double) CONTAINER_RADIUS * CONTAINER_RADIUS) continue;
            observation.items().forEach((item, amount) -> counts.merge(item, amount, Integer::sum));
        }
        return counts;
    }

    private static Map<String, Integer> countVisibleMobs(ServerLevel level, CompanionEntity companion, int radius) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        BlockPos origin = companion.blockPosition();
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, new AABB(origin).inflate(radius))) {
            if (entity == companion || entity instanceof Player) continue;
            Vec3 eye = companion.getEyePosition();
            Vec3 target = entity.getBoundingBox().getCenter();
            if (eye.distanceToSqr(target) > (double) radius * radius) continue;
            if (level.clip(new ClipContext(eye, target, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, companion))
                    .getType() != HitResult.Type.MISS) continue;
            counts.merge(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(), 1, Integer::sum);
        }
        return counts;
    }

    private static Map<String, Integer> countVisibleCrops(ServerLevel level, CompanionEntity companion, ResourceCandidates evidence) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (var observation : evidence.currentVisible(companion, CROP_RADIUS)) {
            var p = observation.location().pos();
            BlockPos pos = new BlockPos(p.x(), p.y(), p.z());
            if (Crops.harvestable(level, pos).isPresent()) counts.merge(observation.blockId(), 1, Integer::sum);
        }
        return counts;
    }
}
