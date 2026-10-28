package dev.friendai.features.construction.blueprint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BlueprintParserTest {
    private static final Set<String> BLOCKS = Set.of(
            "minecraft:cobblestone",
            "minecraft:oak_planks",
            "minecraft:oak_log",
            "minecraft:glass_pane",
            "minecraft:air",
            "minecraft:stone",
            "minecraft:oak_stairs",
            "minecraft:oak_door",
            "minecraft:dirt");

    private static final String EXAMPLE = """
            {
              "name": "small_house",
              "palette": { "C": "minecraft:cobblestone", "P": "minecraft:oak_planks", "L": "minecraft:oak_log",
                           "G": "minecraft:glass_pane", "D": "minecraft:oak_door[facing=south,half=lower]", "_": "minecraft:air" },
              "ops": [
                { "op": "box", "from": [0,0,0], "to": [6,0,6], "block": "C" },
                { "op": "walls", "from": [0,1,0], "to": [6,3,6], "block": "P", "corners": "L" },
                { "op": "roof", "from": [0,4,0], "to": [6,4,6], "style": "gable", "block": "minecraft:oak_stairs", "axis": "x" }
              ]
            }
            """;

    private static String baseId(String state) {
        int bracket = state.indexOf('[');
        return bracket < 0 ? state : state.substring(0, bracket);
    }

    private static boolean known(String state) {
        return BLOCKS.contains(baseId(state));
    }

    private static ParseResult parse(String json) {
        return BlueprintParser.parse(json, BlueprintParserTest::known);
    }

    private static Blueprint parseOk(String json) {
        ParseResult result = parse(json);
        assertTrue(result.errors().isEmpty(), "unexpected errors: " + result.errors());
        assertTrue(result.blueprint().isPresent(), "no blueprint");
        return result.blueprint().get();
    }

    private static List<String> errors(String json) {
        ParseResult result = parse(json);
        assertTrue(result.blueprint().isEmpty(), "blueprint should not be produced when there are errors");
        assertFalse(result.errors().isEmpty(), "expected errors");
        return result.errors();
    }

    private static BlockSpec at(Blueprint blueprint, int x, int y, int z) {
        Optional<BlockSpec> found = blueprint.blocks().stream()
                .filter(b -> b.x() == x && b.y() == y && b.z() == z)
                .findFirst();
        assertTrue(found.isPresent(), "no block at " + x + "," + y + "," + z);
        return found.get();
    }

    private static boolean has(Blueprint blueprint, int x, int y, int z) {
        return blueprint.blocks().stream().anyMatch(b -> b.x() == x && b.y() == y && b.z() == z);
    }

    @Test
    void fullExample() {
        Blueprint blueprint = parseOk(EXAMPLE);
        assertEquals("small_house", blueprint.name());
        assertEquals(7, blueprint.width());
        assertEquals(8, blueprint.height());
        assertEquals(7, blueprint.depth());
        assertEquals(170, blueprint.blocks().size());
        assertEquals("minecraft:oak_log", at(blueprint, 0, 1, 0).state());
        assertEquals("minecraft:oak_log", at(blueprint, 6, 3, 6).state());
        assertEquals("minecraft:cobblestone", at(blueprint, 3, 0, 3).state());
        assertEquals("minecraft:oak_planks", at(blueprint, 3, 1, 0).state());
    }

    @Test
    void layersOverrideOps() {
        Blueprint blueprint = parseOk("""
                {
                  "palette": { "C": "minecraft:stone", "A": "minecraft:air" },
                  "ops": [ { "op": "box", "from": [0,0,0], "to": [1,0,1], "block": "C" } ],
                  "layers": [ [ "A.", ".C" ] ]
                }
                """);
        assertEquals("minecraft:air", at(blueprint, 0, 0, 0).state());
        assertEquals("minecraft:stone", at(blueprint, 1, 0, 1).state());
        assertEquals("minecraft:stone", at(blueprint, 1, 0, 0).state());
    }

    @Test
    void dotLeavesOut() {
        Blueprint blueprint = parseOk("""
                {
                  "palette": { "C": "minecraft:stone" },
                  "layers": [ [ "C.", ".C" ] ]
                }
                """);
        assertEquals(2, blueprint.blocks().size());
        assertFalse(has(blueprint, 1, 0, 0), "a dot leaves the position out");
        assertTrue(has(blueprint, 0, 0, 0));
        assertTrue(has(blueprint, 1, 0, 1));
    }

    @Test
    void airKept() {
        Blueprint blueprint = parseOk("""
                {
                  "palette": { "_": "minecraft:air" },
                  "layers": [ [ "_" ] ]
                }
                """);
        assertEquals(1, blueprint.blocks().size());
        assertEquals("minecraft:air", at(blueprint, 0, 0, 0).state());
    }

    @Test
    void badPaletteKey() {
        List<String> errors = errors("""
                { "palette": { "AB": "minecraft:stone" }, "layers": [ [ "A" ] ] }
                """);
        assertTrue(errors.contains("palette: key \"AB\" must be one character"), errors.toString());
    }

    @Test
    void reservedPaletteKey() {
        List<String> errors = errors("""
                { "palette": { ".": "minecraft:stone" }, "layers": [ [ "." ] ] }
                """);
        assertTrue(errors.contains("palette: key \".\" is reserved"), errors.toString());
    }

    @Test
    void unknownOp() {
        List<String> errors = errors("""
                {
                  "palette": { "C": "minecraft:stone" },
                  "ops": [
                    { "op": "box", "from": [0,0,0], "to": [1,0,1], "block": "C" },
                    { "op": "arch", "from": [0,0,0], "to": [1,0,1], "block": "C" }
                  ]
                }
                """);
        assertTrue(errors.contains("ops[1]: unknown op 'arch'"), errors.toString());
    }

    @Test
    void toBelowFrom() {
        List<String> errors = errors("""
                {
                  "palette": { "C": "minecraft:stone" },
                  "ops": [
                    { "op": "box", "from": [0,0,0], "to": [1,0,1], "block": "C" },
                    { "op": "box", "from": [0,2,0], "to": [1,1,1], "block": "C" }
                  ]
                }
                """);
        assertTrue(errors.contains("ops[1]: 'to' is below 'from'"), errors.toString());
    }

    @Test
    void unknownSymbol() {
        List<String> errors = errors("""
                {
                  "palette": { "C": "minecraft:stone" },
                  "layers": [
                    [ "C" ],
                    [ "C" ],
                    [ "C", "C", "C", "X" ]
                  ]
                }
                """);
        assertTrue(errors.contains("layer 2, row 3: unknown symbol 'X'"), errors.toString());
    }

    @Test
    void rowLength() {
        List<String> errors = errors("""
                {
                  "palette": { "C": "minecraft:stone" },
                  "layers": [
                    [ "C" ],
                    [ "CCCCCCC", "CCCCCCC", "CCCCCCC", "CCCCCCC", "CCCCCC" ]
                  ]
                }
                """);
        assertTrue(errors.contains("layer 1: row 4 has length 6, expected 7"), errors.toString());
    }

    @Test
    void sizeLimit() {
        List<String> errors = errors("""
                {
                  "palette": { "C": "minecraft:stone" },
                  "ops": [ { "op": "box", "from": [0,0,0], "to": [39,4,6], "block": "C" } ]
                }
                """);
        assertTrue(errors.contains("size 40×5×7 exceeds 32"), errors.toString());
    }

    @Test
    void blockLimit() {
        List<String> errors = errors("""
                {
                  "palette": { "C": "minecraft:stone" },
                  "ops": [ { "op": "box", "from": [0,0,0], "to": [31,8,31], "block": "C" } ]
                }
                """);
        assertTrue(errors.contains("block count 9216 exceeds 8192"), errors.toString());
    }

    @Test
    void badPaletteValueReportedOnce() {
        List<String> errors = errors("""
                {
                  "palette": { "C": "minecraft:not_a_block" },
                  "ops": [ { "op": "box", "from": [0,0,0], "to": [1,0,1], "block": "C" } ]
                }
                """);
        assertEquals(List.of("unknown block minecraft:not_a_block"), errors);
    }

    @Test
    void unknownBlock() {
        List<String> errors = errors("""
                {
                  "ops": [ { "op": "box", "from": [0,0,0], "to": [1,0,1], "block": "minecraft:oak_plank" } ]
                }
                """);
        assertTrue(errors.contains("unknown block minecraft:oak_plank"), errors.toString());
    }

    @Test
    void severalErrorsTogether() {
        List<String> errors = errors("""
                {
                  "palette": { "C": "minecraft:stone" },
                  "ops": [
                    { "op": "arch", "from": [0,0,0], "to": [1,0,1], "block": "C" },
                    { "op": "box", "from": [0,0,0], "to": [1,0,1], "block": "minecraft:oak_plank" }
                  ],
                  "layers": [ [ "Q" ] ]
                }
                """);
        assertTrue(errors.contains("ops[0]: unknown op 'arch'"), errors.toString());
        assertTrue(errors.contains("unknown block minecraft:oak_plank"), errors.toString());
        assertTrue(errors.contains("layer 0, row 0: unknown symbol 'Q'"), errors.toString());
    }

    @Test
    void gableRoofFacesOutward() {
        Blueprint blueprint = parseOk("""
                {
                  "palette": { "S": "minecraft:oak_stairs" },
                  "ops": [ { "op": "roof", "from": [0,0,0], "to": [4,0,4], "style": "gable", "block": "S", "axis": "z" } ]
                }
                """);
        assertEquals(
                "minecraft:oak_stairs[facing=east,half=bottom]",
                at(blueprint, 0, 0, 0).state());
        assertEquals(
                "minecraft:oak_stairs[facing=west,half=bottom]",
                at(blueprint, 4, 0, 0).state());
        assertEquals(
                "minecraft:oak_stairs[facing=east,half=bottom]",
                at(blueprint, 1, 1, 2).state());
        assertEquals(
                "minecraft:oak_stairs[facing=west,half=bottom]",
                at(blueprint, 3, 1, 2).state());
        assertTrue(has(blueprint, 2, 2, 0), "ridge row must be present");
        assertEquals("minecraft:oak_planks", at(blueprint, 2, 2, 0).state());
        assertEquals(3, blueprint.height());
    }

    @Test
    void pyramidCornersGetOuterShapes() {
        Blueprint blueprint = parseOk("""
                {
                  "palette": { "S": "minecraft:oak_stairs" },
                  "ops": [ { "op": "roof", "from": [0,0,0], "to": [4,0,4], "style": "pyramid", "block": "S" } ]
                }
                """);
        assertEquals(
                "minecraft:oak_stairs[facing=south,half=bottom,shape=outer_left]",
                at(blueprint, 0, 0, 0).state());
        assertEquals(
                "minecraft:oak_stairs[facing=south,half=bottom,shape=outer_right]",
                at(blueprint, 4, 0, 0).state());
        assertEquals(
                "minecraft:oak_stairs[facing=north,half=bottom,shape=outer_right]",
                at(blueprint, 0, 0, 4).state());
        assertEquals(
                "minecraft:oak_stairs[facing=north,half=bottom,shape=outer_left]",
                at(blueprint, 4, 0, 4).state());
        assertEquals(
                "minecraft:oak_stairs[facing=south,half=bottom]",
                at(blueprint, 1, 0, 0).state());
    }

    @Test
    void aGableRoofHasNoOuterShapes() {
        Blueprint blueprint = parseOk(EXAMPLE);
        for (BlockSpec spec : blueprint.blocks()) {
            if (spec.state().contains("_stairs")) {
                assertFalse(spec.state().contains("shape=outer"), spec.state());
            }
        }
    }

    @Test
    void aBoxStairKeepsItsOwnShape() {
        Blueprint blueprint = parseOk("""
                {
                  "palette": { "S": "minecraft:oak_stairs[facing=east,half=bottom,shape=inner_left]" },
                  "ops": [ { "op": "box", "from": [0,0,0], "to": [0,0,0], "block": "S" } ]
                }
                """);
        assertEquals(
                "minecraft:oak_stairs[facing=east,half=bottom,shape=inner_left]",
                at(blueprint, 0, 0, 0).state());
    }

    @Test
    void doorMakesLowerAndUpper() {
        Blueprint blueprint = parseOk("""
                {
                  "palette": { "D": "minecraft:oak_door", "C": "minecraft:cobblestone" },
                  "ops": [
                    { "op": "box", "from": [0,0,0], "to": [2,0,2], "block": "C" },
                    { "op": "door", "at": [2,1,2], "facing": "south", "block": "D" }
                  ]
                }
                """);
        assertEquals(
                "minecraft:oak_door[facing=south,half=lower]",
                at(blueprint, 2, 1, 2).state());
        assertEquals(
                "minecraft:oak_door[facing=south,half=upper]",
                at(blueprint, 2, 2, 2).state());
    }
}
