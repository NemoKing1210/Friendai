package dev.friendai.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StructurePartsTest {
    private static Blueprint parse(String json) {
        ParseResult result = BlueprintParser.parse(json, state -> true);
        return result.blueprint().orElseThrow(() -> new AssertionError(result.errors().toString()));
    }

    private static Blueprint house() {
        return parse("""
                {
                  "name": "hut",
                  "palette": {
                    "C": "minecraft:cobblestone", "P": "minecraft:oak_planks",
                    "R": "minecraft:oak_slab", "D": "minecraft:oak_door",
                    "G": "minecraft:glass_pane"
                  },
                  "ops": [
                    { "op": "box", "from": [0,0,0], "to": [4,0,4], "block": "C" },
                    { "op": "walls", "from": [0,1,0], "to": [4,2,4], "block": "P" },
                    { "op": "roof", "from": [0,3,0], "to": [4,3,4], "style": "flat",
                      "block": "R" },
                    { "op": "door", "at": [2,1,0], "facing": "south", "block": "D" },
                    { "op": "box", "from": [0,1,2], "to": [0,1,2], "block": "G" }
                  ]
                }
                """);
    }

    private static long nonAir(Blueprint blueprint) {
        return blueprint.blocks().stream()
                .filter(spec -> !spec.state().startsWith("minecraft:air"))
                .count();
    }

    @Test
    void aHouseHasEveryPart() {
        Blueprint house = house();
        StructureParts parts = StructureParts.analyse(house);
        assertTrue(parts.count(PartKind.FLOOR) > 0, parts.describe());
        assertTrue(parts.count(PartKind.WALLS) > 0, parts.describe());
        assertTrue(parts.count(PartKind.ROOF) > 0, parts.describe());
        assertTrue(parts.count(PartKind.ENTRANCE) > 0, parts.describe());
        assertTrue(parts.count(PartKind.WINDOW) > 0, parts.describe());
        assertTrue(parts.count(PartKind.INTERIOR) > 0, parts.describe());
        for (BlockSpec spec : house.blocks()) {
            assertNotNull(parts.roleOf(spec), "every spec has a role: " + spec);
        }
        assertEquals(nonAir(house), parts.cells(Set.of()).size(), "all non-air cells");
        assertTrue(parts.describe().contains("floor"), parts.describe());
        assertTrue(parts.describe().contains("interior"), parts.describe());
    }

    @Test
    void aWallHasNoInteriorOrRoof() {
        Blueprint wall = parse("""
                {
                  "palette": { "C": "minecraft:cobblestone" },
                  "ops": [ { "op": "box", "from": [0,0,0], "to": [4,2,0], "block": "C" } ]
                }
                """);
        StructureParts parts = StructureParts.analyse(wall);
        assertEquals(0, parts.count(PartKind.INTERIOR), parts.describe());
        assertEquals(0, parts.count(PartKind.FLOOR), parts.describe());
        assertEquals(0, parts.count(PartKind.ROOF), parts.describe());
        assertEquals(nonAir(wall), parts.count(PartKind.OTHER), parts.describe());
        assertTrue(parts.describe().contains("no enclosed interior"), parts.describe());
    }

    @Test
    void aSolidCubeHasNoInterior() {
        Blueprint cube = parse("""
                {
                  "palette": { "C": "minecraft:stone" },
                  "ops": [ { "op": "box", "from": [0,0,0], "to": [2,2,2], "block": "C" } ]
                }
                """);
        assertEquals(0, StructureParts.analyse(cube).count(PartKind.INTERIOR));
    }

    @Test
    void anOpenTopHasNoInterior() {
        Blueprint open = parse("""
                {
                  "palette": { "C": "minecraft:cobblestone" },
                  "ops": [
                    { "op": "box", "from": [0,0,0], "to": [2,0,2], "block": "C" },
                    { "op": "walls", "from": [0,1,0], "to": [2,2,2], "block": "C" }
                  ]
                }
                """);
        assertEquals(0, StructureParts.analyse(open).count(PartKind.INTERIOR));
    }

    @Test
    void aDoorIsAnEntranceAndGlassIsAWindow() {
        Blueprint pieces = parse("""
                {
                  "palette": {
                    "C": "minecraft:stone", "D": "minecraft:oak_door",
                    "G": "minecraft:glass_pane"
                  },
                  "ops": [
                    { "op": "box", "from": [0,0,0], "to": [0,0,0], "block": "C" },
                    { "op": "door", "at": [0,1,0], "facing": "south", "block": "D" },
                    { "op": "box", "from": [0,1,2], "to": [0,1,2], "block": "G" }
                  ]
                }
                """);
        StructureParts parts = StructureParts.analyse(pieces);
        for (BlockSpec spec : pieces.blocks()) {
            if (spec.state().startsWith("minecraft:oak_door")) {
                assertEquals(PartKind.ENTRANCE, parts.roleOf(spec), spec.toString());
            }
            if (spec.state().startsWith("minecraft:glass_pane")) {
                assertEquals(PartKind.WINDOW, parts.roleOf(spec), spec.toString());
            }
        }
    }

    @Test
    void selectionKeepsOnlyTheChosenParts() {
        Blueprint house = house();
        StructureParts parts = StructureParts.analyse(house);
        List<BlockSpec> roof = parts.cells(Set.of(PartKind.ROOF));
        assertEquals(parts.count(PartKind.ROOF), roof.size());
        assertFalse(roof.isEmpty());
        for (BlockSpec spec : roof) {
            assertEquals(PartKind.ROOF, parts.roleOf(spec));
        }
        assertTrue(parts.cells(Set.of(PartKind.INTERIOR)).isEmpty(), "the interior is empty space");
        assertTrue(parts.describe(Set.of(PartKind.ROOF)).contains("roof"));
    }
}
