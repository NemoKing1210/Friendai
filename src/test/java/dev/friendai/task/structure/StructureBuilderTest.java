package dev.friendai.task.structure;

import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;
import org.junit.jupiter.api.Test;

class StructureBuilderTest {
    @Test
    void theAbsentBuilderBuildsNothing() {
        assertTrue(StructureBuilder.NONE
                .build("nether_portal", BlockPos.ZERO, Rotation.NONE, 4)
                .isEmpty());
    }

    @Test
    void theAbsentBuilderHasNoVolume() {
        assertTrue(StructureBuilder.NONE
                .volume("nether_portal", BlockPos.ZERO, Rotation.NONE)
                .isEmpty());
    }
}
