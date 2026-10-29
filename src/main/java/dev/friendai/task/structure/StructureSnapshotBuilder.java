package dev.friendai.task.structure;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.features.construction.minecraft.build.Placement;
import dev.friendai.features.construction.special.Offset;
import dev.friendai.features.construction.special.Outcome;
import dev.friendai.features.construction.special.SpecialStructure;
import dev.friendai.features.construction.special.StructureSnapshot;
import dev.friendai.features.construction.special.Trigger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/** Reads the world at a site into the pure planner snapshot. Server thread only. */
public final class StructureSnapshotBuilder {
    private StructureSnapshotBuilder() {}

    public static StructureSnapshot build(
            ServerLevel level,
            CompanionEntity companion,
            SpecialStructure structure,
            StructureSite site,
            int maxNearbySpawned) {
        Set<Offset> correct = new HashSet<>();
        Set<BlockPos> correctPositions = new HashSet<>();
        boolean protectedArea = false;
        for (var block : structure.body().blocks()) {
            Offset offset = new Offset(block.x(), block.y(), block.z());
            BlockPos pos = site.pos(structure, offset);
            if (level.getBlockState(pos).equals(site.wanted(block.state()))) {
                correct.add(offset);
                correctPositions.add(pos);
            }
            protectedArea |= !companion.hands().canInteract(pos);
        }
        Offset triggerAt = switch (structure.trigger()) {
            case Trigger.LastBlock t -> t.at();
            case Trigger.Ignite t -> t.at();
        };
        protectedArea |= !companion.hands().canInteract(site.pos(structure, triggerAt));

        AABB box = site.box(structure);
        boolean free = true;
        for (BlockPos pos : cells(box)) {
            if (correctPositions.contains(pos)) {
                continue;
            }
            BlockState state = level.getBlockState(pos);
            free &= state.isAir() || state.canBeReplaced();
        }
        boolean entityInFootprint = false;
        List<String> blockerIds = new ArrayList<>();
        for (LivingEntity entity :
                level.getEntitiesOfClass(LivingEntity.class, box, e -> e != companion && e.isAlive())) {
            entityInFootprint = true;
            if (blockerIds.size() < 3) {
                String id =
                        BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
                if (!blockerIds.contains(id)) {
                    blockerIds.add(id);
                }
            }
        }
        // The companion standing in its own footprint still counts, but is the one entity the task can move.
        entityInFootprint |= companion.getBoundingBox().intersects(box);
        String blockers = String.join(", ", blockerIds);

        int nearby = 0;
        boolean present = false;
        switch (structure.outcome()) {
            case Outcome.EntitySpawned o ->
                nearby = level.getEntitiesOfClass(
                                Entity.class,
                                box.inflate(o.radius()),
                                e -> e.isAlive()
                                        && BuiltInRegistries.ENTITY_TYPE
                                                .getKey(e.getType())
                                                .toString()
                                                .equals(o.entityType()))
                        .size();
            case Outcome.BlocksPresent o -> present = countBlocks(level, box, o.state()) >= o.minCount();
        }
        return new StructureSnapshot(
                free,
                entityInFootprint,
                protectedArea,
                level.dimension() == Level.END,
                correct,
                present,
                nearby,
                maxNearbySpawned,
                bag(companion),
                blockers);
    }

    /** How many positions in the box hold the block of {@code state}. */
    public static int countBlocks(ServerLevel level, AABB box, String state) {
        var block = Placement.parseState(state).getBlock();
        int count = 0;
        for (BlockPos pos : cells(box)) {
            if (level.getBlockState(pos).is(block)) {
                count++;
            }
        }
        return count;
    }

    private static Iterable<BlockPos> cells(AABB box) {
        return BlockPos.betweenClosed(
                BlockPos.containing(box.minX, box.minY, box.minZ),
                BlockPos.containing(box.maxX - 1, box.maxY - 1, box.maxZ - 1));
    }

    private static Map<String, Integer> bag(CompanionEntity companion) {
        Map<String, Integer> items = new HashMap<>();
        for (int slot = 0; slot < companion.inventory().getContainerSize(); slot++) {
            account(items, companion.inventory().getItem(slot));
        }
        account(items, companion.getMainHandItem());
        return items;
    }

    private static void account(Map<String, Integer> items, ItemStack stack) {
        if (!stack.isEmpty()) {
            items.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(), Integer::sum);
        }
    }
}
