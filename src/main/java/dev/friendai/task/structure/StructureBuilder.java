package dev.friendai.task.structure;

import dev.friendai.task.Task;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.AABB;

/**
 * Builds a named special structure at a point. The construction feature supplies the real implementation; a context
 * without one builds nothing, so a task can ask without knowing the feature.
 */
public interface StructureBuilder {
    Optional<Task> build(String structureId, BlockPos origin, Rotation rotation, int maxNearbySpawned);

    Optional<AABB> volume(String structureId, BlockPos origin, Rotation rotation);

    StructureBuilder NONE = new StructureBuilder() {
        @Override
        public Optional<Task> build(String structureId, BlockPos origin, Rotation rotation, int maxNearbySpawned) {
            return Optional.empty();
        }

        @Override
        public Optional<AABB> volume(String structureId, BlockPos origin, Rotation rotation) {
            return Optional.empty();
        }
    };
}
