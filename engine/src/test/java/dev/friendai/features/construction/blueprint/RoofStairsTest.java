package dev.friendai.features.construction.blueprint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

class RoofStairsTest {
    private static final Set<String> KNOWN = Set.of(
            "minecraft:oak_stairs",
            "minecraft:oak_slab",
            "minecraft:oak_planks",
            "minecraft:jungle_stairs",
            "minecraft:jungle_planks",
            "minecraft:stone",
            "minecraft:stone_stairs",
            "minecraft:stone_bricks",
            "minecraft:stone_brick_stairs",
            "minecraft:bricks",
            "minecraft:brick_stairs",
            "minecraft:deepslate_tiles",
            "minecraft:deepslate_tile_stairs",
            "minecraft:mud_bricks",
            "minecraft:mud_brick_stairs",
            "minecraft:quartz_block",
            "minecraft:quartz_stairs",
            "minecraft:smooth_quartz",
            "minecraft:smooth_quartz_stairs",
            "minecraft:purpur_block",
            "minecraft:purpur_stairs",
            "minecraft:glass");

    private static final Predicate<String> KNOWN_STATE = RoofStairsTest::known;

    private static boolean known(String id) {
        return KNOWN.contains(RoofStairs.base(id));
    }

    private static String stairs(String block) {
        return RoofStairs.stairsFor(block, KNOWN_STATE).orElse(null);
    }

    @Test
    void explicitStairsAreKept() {
        assertEquals("minecraft:oak_stairs", stairs("minecraft:oak_stairs"));
        assertEquals("minecraft:oak_stairs", stairs("minecraft:oak_stairs[facing=east,half=bottom]"));
    }

    @Test
    void planksAndSlabsMapToTheirStairs() {
        assertEquals("minecraft:oak_stairs", stairs("minecraft:oak_planks"));
        assertEquals("minecraft:jungle_stairs", stairs("minecraft:jungle_planks"));
        assertEquals("minecraft:oak_stairs", stairs("minecraft:oak_slab"));
    }

    @Test
    void fullBlocksUseSuffixesAndIrregularNames() {
        assertEquals("minecraft:stone_stairs", stairs("minecraft:stone"));
        assertEquals("minecraft:stone_brick_stairs", stairs("minecraft:stone_bricks"));
        assertEquals("minecraft:brick_stairs", stairs("minecraft:bricks"));
        assertEquals("minecraft:deepslate_tile_stairs", stairs("minecraft:deepslate_tiles"));
        assertEquals("minecraft:mud_brick_stairs", stairs("minecraft:mud_bricks"));
        assertEquals("minecraft:quartz_stairs", stairs("minecraft:quartz_block"));
        assertEquals("minecraft:smooth_quartz_stairs", stairs("minecraft:smooth_quartz"));
        assertEquals("minecraft:purpur_stairs", stairs("minecraft:purpur_block"));
    }

    @Test
    void aBlockWithoutAStairsVariantIsEmpty() {
        assertTrue(RoofStairs.stairsFor("minecraft:glass", KNOWN_STATE).isEmpty());
        assertTrue(RoofStairs.stairsFor("minecraft:oak_planks", id -> false).isEmpty());
    }

    @Test
    void onlyKnownIdsAreReturned() {
        Optional<String> stairs = RoofStairs.stairsFor("minecraft:oak_planks", id -> false);
        assertTrue(stairs.isEmpty(), "a missing stairs block must not be invented");
    }
}
