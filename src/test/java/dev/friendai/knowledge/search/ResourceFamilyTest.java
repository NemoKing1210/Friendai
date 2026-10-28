package dev.friendai.knowledge.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ResourceFamilyTest {
    @Test
    void logsBecomeAnyWood() {
        assertEquals(
                Optional.of("#minecraft:logs"),
                ResourceFamily.ofBlocks(
                        Set.of("minecraft:acacia_log", "minecraft:oak_log", "minecraft:dark_oak_wood")));
        assertEquals(
                Optional.of("#minecraft:logs"),
                ResourceFamily.ofBlocks(Set.of("minecraft:crimson_stem", "minecraft:warped_hyphae")));
    }

    @Test
    void leavesBecomeLeaves() {
        assertEquals(
                Optional.of("#minecraft:leaves"),
                ResourceFamily.ofBlocks(Set.of("minecraft:oak_leaves", "minecraft:acacia_leaves")));
    }

    @Test
    void oresBecomeTheirItemFamily() {
        assertEquals(
                Optional.of("minecraft:raw_iron"),
                ResourceFamily.ofBlocks(Set.of("minecraft:iron_ore", "minecraft:deepslate_iron_ore")));
        assertEquals(
                Optional.of("minecraft:coal"),
                ResourceFamily.ofBlocks(Set.of("minecraft:coal_ore", "minecraft:deepslate_coal_ore")));
    }

    @Test
    void singleBlockKeepsItsId() {
        assertEquals(Optional.of("minecraft:stone"), ResourceFamily.ofBlocks(Set.of("minecraft:stone")));
    }

    @Test
    void mixedIdsHaveNoFamily() {
        assertTrue(ResourceFamily.ofBlocks(Set.of("minecraft:oak_log", "minecraft:stone"))
                .isEmpty());
    }
}
