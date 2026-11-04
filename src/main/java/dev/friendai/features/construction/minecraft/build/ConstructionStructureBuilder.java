package dev.friendai.features.construction.minecraft.build;

import dev.friendai.features.construction.minecraft.task.SpecialStructureTask;
import dev.friendai.features.construction.special.StructureBook;
import dev.friendai.task.Task;
import dev.friendai.task.structure.StructureBuilder;
import dev.friendai.task.structure.StructureSite;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.AABB;

/** The construction feature's structure builder: the built-in special structures and their footprints. */
public final class ConstructionStructureBuilder implements StructureBuilder {
    @Override
    public Optional<Task> build(String structureId, BlockPos origin, Rotation rotation, int maxNearbySpawned) {
        return StructureBook.withBuiltins()
                .get(structureId)
                .map(structure -> new SpecialStructureTask(structure, origin, rotation, maxNearbySpawned));
    }

    @Override
    public Optional<AABB> volume(String structureId, BlockPos origin, Rotation rotation) {
        return StructureBook.withBuiltins()
                .get(structureId)
                .map(structure -> new StructureSite(origin, rotation).box(structure));
    }
}
