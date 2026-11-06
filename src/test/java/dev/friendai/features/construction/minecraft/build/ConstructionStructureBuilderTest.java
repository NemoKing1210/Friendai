package dev.friendai.features.construction.minecraft.build;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.friendai.features.construction.special.StructureBook;
import dev.friendai.task.structure.StructureSite;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

class ConstructionStructureBuilderTest {
    @Test
    void theVolumeMatchesTheSiteBox() {
        BlockPos origin = new BlockPos(12, 65, -8);
        AABB expected = new StructureSite(origin, Rotation.NONE)
                .box(StructureBook.withBuiltins().get("nether_portal").orElseThrow());

        assertEquals(
                expected,
                new ConstructionStructureBuilder()
                        .volume("nether_portal", origin, Rotation.NONE)
                        .orElseThrow());
    }
}
