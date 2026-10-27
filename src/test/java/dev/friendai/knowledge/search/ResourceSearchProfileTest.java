package dev.friendai.knowledge.search;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalInt;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ResourceSearchProfileTest {
    @Test
    void strategyAndDimensionMustBePermitted() {
        var profile = new ResourceSearchProfile("minecraft:diamond", Set.of("minecraft:overworld"),
                OptionalInt.of(-58), Set.of(ResourceSearchProfile.Strategy.CAVE));
        assertTrue(profile.allows("minecraft:overworld", ResourceSearchProfile.Strategy.CAVE));
        assertFalse(profile.allows("minecraft:the_nether", ResourceSearchProfile.Strategy.CAVE));
        assertFalse(profile.allows("minecraft:overworld", ResourceSearchProfile.Strategy.SURFACE));
    }

    @Test
    void unknownModdedOreHasNoInventedDepthOrSearchStrategy() {
        var profile = ResourceSearchProfile.builtin("example:deep_ore", "minecraft:overworld");
        assertTrue(profile.targetY().isEmpty());
        assertTrue(profile.strategies().isEmpty());
    }

    @Test
    void woodFamilyIsSurface() {
        for (String family : new String[]{
                "#minecraft:logs", "#minecraft:leaves", "#minecraft:crops",
                "minecraft:oak_wood", "minecraft:oak_planks"}) {
            var profile = ResourceSearchProfile.builtin(family, "minecraft:overworld");
            assertTrue(profile.allows("minecraft:overworld", ResourceSearchProfile.Strategy.SURFACE), family);
        }
    }
}
