package dev.friendai.features.construction.blueprint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GeneratorsTest {
    private static final Set<String> BLOCKS = Set.of(
            "minecraft:cobblestone",
            "minecraft:oak_planks",
            "minecraft:oak_log",
            "minecraft:glass_pane",
            "minecraft:oak_stairs",
            "minecraft:oak_slab",
            "minecraft:oak_door",
            "minecraft:stone",
            "minecraft:stone_stairs",
            "minecraft:stone_bricks",
            "minecraft:stone_brick_stairs",
            "minecraft:oak_fence",
            "minecraft:oak_fence_gate",
            "minecraft:ladder",
            "minecraft:torch",
            "minecraft:wall_torch");

    private static boolean known(String state) {
        int bracket = state.indexOf('[');
        return BLOCKS.contains(bracket < 0 ? state : state.substring(0, bracket));
    }

    private static ParseResult generate(String json) {
        return Generators.generate(JsonParser.parseString(json).getAsJsonObject(), GeneratorsTest::known);
    }

    private static Blueprint generateOk(String json) {
        ParseResult result = generate(json);
        assertTrue(result.errors().isEmpty(), "unexpected errors: " + result.errors());
        return result.blueprint().orElseThrow();
    }

    private static boolean has(Blueprint blueprint, int x, int y, int z) {
        return blueprint.blocks().stream().anyMatch(b -> b.x() == x && b.y() == y && b.z() == z);
    }

    private static String stateAt(Blueprint blueprint, int x, int y, int z) {
        return blueprint.blocks().stream()
                .filter(b -> b.x() == x && b.y() == y && b.z() == z)
                .map(BlockSpec::state)
                .findFirst()
                .orElse(null);
    }

    private static long count(Blueprint blueprint, String id) {
        return blueprint.blocks().stream().filter(b -> b.state().startsWith(id)).count();
    }

    private static final String HOUSE = """
            { "type": "house", "width": 7, "depth": 7, "floors": 1,
              "walls": "minecraft:oak_planks", "floor": "minecraft:cobblestone", "roof": "minecraft:oak_stairs" }
            """;

    @Test
    void houseTorchesAreOnTheWalls() {
        Blueprint house = generateOk("""
                { "type": "house", "width": 7, "depth": 7, "floors": 2,
                  "walls": "minecraft:oak_planks", "floor": "minecraft:cobblestone",
                  "roof": "minecraft:oak_stairs", "torches": true }
                """);
        long torches =
                house.blocks().stream().filter(b -> b.state().contains("torch")).count();
        assertEquals(4, torches, "two torches per floor");
        for (BlockSpec spec : house.blocks()) {
            if (spec.state().contains("torch")) {
                assertTrue(
                        spec.state().startsWith("minecraft:wall_torch[facing="),
                        "a torch above the ground floor needs a wall: " + spec.state());
            }
        }
    }

    @Test
    void houseBasics() {
        Blueprint house = generateOk(HOUSE);
        assertEquals(7, house.width());
        assertEquals(7, house.depth());
        assertEquals("minecraft:cobblestone", stateAt(house, 3, 0, 3));
        for (int y = 1; y <= 3; y++) {
            assertEquals("minecraft:oak_planks", stateAt(house, 0, y, 3), "wall at y=" + y);
        }
        assertFalse(has(house, 0, 4, 3), "walls are three blocks tall");
        assertEquals("minecraft:oak_door[facing=south,half=lower]", stateAt(house, 3, 1, 6));
        assertEquals("minecraft:oak_door[facing=south,half=upper]", stateAt(house, 3, 2, 6));
        long westWindows = house.blocks().stream()
                .filter(b -> b.x() == 0 && b.state().startsWith("minecraft:glass_pane"))
                .count();
        assertEquals(1, westWindows, "one window per wall for a 7-long wall: (7-2)/3");
        assertEquals(4, count(house, "minecraft:glass_pane"), "one window on each of the four walls");
        assertTrue(stateAt(house, 3, 4, 0).startsWith("minecraft:oak_stairs"), "north slope at y=4");
        assertTrue(stateAt(house, 3, 4, 6).startsWith("minecraft:oak_stairs"), "south slope at y=4");
        assertTrue(stateAt(house, 3, 7, 3).startsWith("minecraft:oak_planks"), "ridge row on top");
    }

    @Test
    void houseRoofPlanksBecomeStairs() {
        Blueprint house = generateOk("""
                { "type": "house", "width": 7, "depth": 7, "floors": 1,
                  "walls": "minecraft:oak_planks", "floor": "minecraft:cobblestone", "roof": "minecraft:oak_planks" }
                """);
        assertTrue(stateAt(house, 3, 4, 0).startsWith("minecraft:oak_stairs"), "north slope");
        assertTrue(stateAt(house, 3, 4, 6).startsWith("minecraft:oak_stairs"), "south slope");
        assertTrue(stateAt(house, 3, 7, 3).startsWith("minecraft:oak_planks"), "ridge");
    }

    @Test
    void houseRoofSlabBecomesStairs() {
        Blueprint house = generateOk("""
                { "type": "house", "width": 7, "depth": 7, "floors": 1,
                  "walls": "minecraft:oak_planks", "floor": "minecraft:cobblestone", "roof": "minecraft:oak_slab" }
                """);
        assertTrue(stateAt(house, 3, 4, 0).startsWith("minecraft:oak_stairs"), "north slope");
        assertTrue(stateAt(house, 3, 7, 3).startsWith("minecraft:oak_planks"), "ridge");
    }

    @Test
    void houseRoofFullBlocksUseTheirStairs() {
        Blueprint stone = generateOk("""
                { "type": "house", "width": 7, "depth": 7,
                  "walls": "minecraft:oak_planks", "floor": "minecraft:cobblestone", "roof": "minecraft:stone" }
                """);
        assertTrue(stateAt(stone, 3, 4, 0).startsWith("minecraft:stone_stairs"), "stone slope");
        assertEquals("minecraft:stone", stateAt(stone, 3, 7, 3), "stone ridge");

        Blueprint bricks = generateOk("""
                { "type": "house", "width": 7, "depth": 7,
                  "walls": "minecraft:oak_planks", "floor": "minecraft:cobblestone", "roof": "minecraft:stone_bricks" }
                """);
        assertTrue(stateAt(bricks, 3, 4, 0).startsWith("minecraft:stone_brick_stairs"), "brick slope");
        assertEquals("minecraft:stone_bricks", stateAt(bricks, 3, 7, 3), "brick ridge");
    }

    @Test
    void houseRoofWithoutAStairsVariantFailsClearly() {
        ParseResult result = generate("""
                { "type": "house", "width": 7, "depth": 7,
                  "walls": "minecraft:oak_planks", "floor": "minecraft:cobblestone", "roof": "minecraft:glass" }
                """);
        assertTrue(result.blueprint().isEmpty(), "an impossible roof must not build");
        assertTrue(
                result.errors().stream()
                        .anyMatch(
                                e -> e.contains("roof minecraft:glass has no stairs") && e.contains("roof_style flat")),
                "actionable error: " + result.errors());
    }

    @Test
    void houseRoofDefaultsToTheWallsStairs() {
        ParseResult result = generate("""
                { "type": "house", "width": 7, "depth": 7,
                  "walls": "minecraft:oak_planks", "floor": "minecraft:cobblestone" }
                """);
        assertTrue(result.errors().isEmpty(), "unexpected errors: " + result.errors());
        Blueprint house = result.blueprint().orElseThrow();
        assertTrue(stateAt(house, 3, 4, 0).startsWith("minecraft:oak_stairs"), "slope from the walls");
        assertTrue(result.notes().stream().anyMatch(n -> n.contains("roof omitted")), "note: " + result.notes());
    }

    @Test
    void houseFrameLogs() {
        Blueprint house = generateOk("""
                { "type": "house", "width": 7, "depth": 7, "floors": 1,
                  "walls": "minecraft:oak_planks", "floor": "minecraft:cobblestone", "roof": "minecraft:oak_stairs",
                  "frame": "minecraft:oak_log" }
                """);
        assertEquals("minecraft:oak_log", stateAt(house, 0, 1, 0));
        assertEquals("minecraft:oak_log", stateAt(house, 6, 3, 6));
        assertEquals("minecraft:oak_log", stateAt(house, 0, 0, 3), "floor beams run along the floor edge");
    }

    @Test
    void houseTwoFloors() {
        Blueprint house = generateOk("""
                { "type": "house", "width": 7, "depth": 7, "floors": 2,
                  "walls": "minecraft:oak_planks", "floor": "minecraft:cobblestone", "roof": "minecraft:oak_stairs" }
                """);
        assertEquals("minecraft:oak_planks", stateAt(house, 0, 6, 3), "two floors of three blocks");
        assertEquals(8, count(house, "minecraft:glass_pane"), "one window per wall per floor");
    }

    @Test
    void houseDoorSide() {
        Blueprint house = generateOk("""
                { "type": "house", "width": 7, "depth": 7,
                  "walls": "minecraft:oak_planks", "floor": "minecraft:cobblestone", "roof": "minecraft:oak_stairs",
                  "door_side": "north" }
                """);
        assertEquals("minecraft:oak_door[facing=north,half=lower]", stateAt(house, 3, 1, 0));
    }

    @Test
    void towerWithLadder() {
        Blueprint tower = generateOk("""
                { "type": "tower", "size": 5, "height": 6, "walls": "minecraft:stone_bricks", "ladder": true }
                """);
        assertEquals("minecraft:stone_bricks", stateAt(tower, 0, 3, 0));
        assertTrue(
                stateAt(tower, 2, 3, 1).startsWith("minecraft:ladder[facing=south]"), "ladder inside the north wall");
        assertTrue(has(tower, 0, 6, 0), "crenellation on the top edge");
        assertFalse(has(tower, 1, 6, 0), "crenellations alternate");
    }

    @Test
    void wallAxis() {
        Blueprint alongZ = generateOk("""
                { "type": "wall", "length": 5, "height": 3, "block": "minecraft:cobblestone", "axis": "z" }
                """);
        assertTrue(has(alongZ, 0, 2, 4));
        assertFalse(has(alongZ, 4, 2, 0));
        Blueprint alongX = generateOk("""
                { "type": "wall", "length": 5, "height": 3, "block": "minecraft:cobblestone" }
                """);
        assertTrue(has(alongX, 4, 2, 0));
        assertFalse(has(alongX, 0, 2, 4));
    }

    @Test
    void fenceWithGate() {
        Blueprint fence = generateOk("""
                { "type": "fence", "width": 7, "depth": 5, "block": "minecraft:oak_fence" }
                """);
        assertEquals("minecraft:oak_fence", stateAt(fence, 0, 0, 0));
        assertTrue(stateAt(fence, 3, 0, 4).startsWith("minecraft:oak_fence_gate"), "gate in the south wall middle");
    }

    @Test
    void platform() {
        Blueprint platform = generateOk("""
                { "type": "platform", "width": 4, "depth": 3, "block": "minecraft:stone" }
                """);
        assertEquals(12, platform.blocks().size());
        assertEquals(4, platform.width());
        assertEquals(3, platform.depth());
        assertEquals(1, platform.height());
        assertTrue(has(platform, 3, 0, 2));
    }

    @Test
    void bridgeRailing() {
        Blueprint bridge = generateOk("""
                { "type": "bridge", "length": 5, "width": 3, "block": "minecraft:stone", "railing": "minecraft:oak_fence" }
                """);
        assertTrue(has(bridge, 2, 0, 1), "deck");
        assertTrue(has(bridge, 0, 1, 0), "railing on one long side");
        assertTrue(has(bridge, 4, 1, 2), "railing on the other long side");
        assertFalse(has(bridge, 2, 1, 1), "no railing on the deck");
    }

    @Test
    void rangeErrors() {
        List<String> errors = generate("""
                { "type": "house", "width": 4, "depth": 16, "floors": 4,
                  "walls": "minecraft:oak_planks", "floor": "minecraft:cobblestone", "roof": "minecraft:oak_stairs" }
                """).errors();
        assertTrue(errors.contains("width 4 is below 5"), errors.toString());
        assertTrue(errors.contains("depth 16 is above 15"), errors.toString());
        assertTrue(errors.contains("floors 4 is above 3"), errors.toString());
    }

    @Test
    void unknownType() {
        assertEquals(
                List.of("unknown type 'castle'"),
                generate("{ \"type\": \"castle\" }").errors());
    }

    @Test
    void missingRequiredBlock() {
        List<String> errors = generate("""
                { "type": "wall", "length": 5, "height": 3 }
                """).errors();
        assertTrue(errors.contains("block is required"), errors.toString());
    }
}
