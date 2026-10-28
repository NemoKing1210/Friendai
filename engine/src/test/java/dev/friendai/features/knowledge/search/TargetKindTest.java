package dev.friendai.features.knowledge.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import dev.friendai.features.navigation.pathing.Pos;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TargetKindTest {
    private static final Pos HERE = new Pos(0, 64, 0);

    private static TargetKind.CandidateFacts facts(
            Set<String> visible, Set<String> known, Set<String> entities, Set<String> places, String biome) {
        return new TargetKind.CandidateFacts(HERE, "minecraft:overworld", biome, visible, known, entities, places);
    }

    private static TargetKind.BlockFamily logs() {
        return new TargetKind.BlockFamily(
                "#minecraft:logs",
                Set.of("minecraft:oak_log", "minecraft:birch_log"),
                new ResourceSearchProfile(
                        "#minecraft:logs",
                        Set.of("minecraft:overworld"),
                        OptionalInt.empty(),
                        Set.of(ResourceSearchProfile.Strategy.SURFACE)));
    }

    @Test
    void blockFamilyMatchesVisibleAndRememberedBlocks() {
        assertTrue(logs().matches(facts(Set.of("minecraft:oak_log"), Set.of(), Set.of(), Set.of(), "")));
        assertTrue(logs().matches(facts(Set.of(), Set.of("minecraft:birch_log"), Set.of(), Set.of(), "")));
        assertFalse(logs().matches(facts(Set.of("minecraft:stone"), Set.of(), Set.of(), Set.of(), "")));
    }

    @Test
    void structureMatchesAnyMarkerAndHonoursTheMinimum() {
        var village = StructureMarkers.get("village").orElseThrow();
        var once = new TargetKind.Structure("minecraft:village", village.markers(), village.minimum(), 64);
        assertTrue(once.matches(facts(Set.of("minecraft:bell"), Set.of(), Set.of(), Set.of(), "")));

        var both = new TargetKind.Structure(
                "minecraft:woodland_mansion", Set.of("minecraft:dark_oak_planks", "minecraft:birch_planks"), 2, 64);
        assertFalse(both.matches(facts(Set.of("minecraft:dark_oak_planks"), Set.of(), Set.of(), Set.of(), "")));
        assertTrue(both.matches(facts(
                Set.of("minecraft:dark_oak_planks", "minecraft:birch_planks"), Set.of(), Set.of(), Set.of(), "")));
    }

    @Test
    void structureMarkersMustStandInOneCluster() {
        var mansion = new TargetKind.Structure(
                "minecraft:woodland_mansion", Set.of("minecraft:dark_oak_planks", "minecraft:birch_planks"), 2, 64);
        var far = new TargetKind.CandidateFacts(
                HERE,
                "minecraft:overworld",
                "",
                Set.of("minecraft:dark_oak_planks", "minecraft:birch_planks"),
                Set.of(),
                Set.of(),
                Set.of(),
                Map.of(new Pos(0, 64, 0), "minecraft:dark_oak_planks", new Pos(100, 64, 0), "minecraft:birch_planks"));
        var near = new TargetKind.CandidateFacts(
                HERE,
                "minecraft:overworld",
                "",
                Set.of("minecraft:dark_oak_planks", "minecraft:birch_planks"),
                Set.of(),
                Set.of(),
                Set.of(),
                Map.of(new Pos(0, 64, 0), "minecraft:dark_oak_planks", new Pos(8, 64, 0), "minecraft:birch_planks"));
        assertFalse(mansion.matches(far));
        assertTrue(mansion.matches(near));
    }

    @Test
    void biomeMatchesThePositionNotASighting() {
        var forest = new TargetKind.Biome("minecraft:forest", 64);
        assertTrue(forest.matches(facts(Set.of(), Set.of(), Set.of(), Set.of(), "minecraft:forest")));
        assertFalse(forest.matches(facts(Set.of(), Set.of(), Set.of(), Set.of(), "minecraft:ocean")));
    }

    @Test
    void entityMatchesOnlyVisibleIds() {
        var villager = new TargetKind.Entity("minecraft:villager", 32);
        assertTrue(villager.matches(facts(Set.of(), Set.of(), Set.of("minecraft:villager"), Set.of(), "")));
        assertFalse(villager.matches(facts(Set.of(), Set.of(), Set.of("minecraft:cow"), Set.of(), "")));
    }

    @Test
    void placeMatchesCaseInsensitively() {
        var home = new TargetKind.Place("Home", "minecraft:overworld", 64);
        assertTrue(home.matches(facts(Set.of(), Set.of(), Set.of(), Set.of("home"), "")));
        assertFalse(home.matches(facts(Set.of(), Set.of(), Set.of(), Set.of("mine"), "")));
    }

    @Test
    void roundTripPreservesEveryKind() {
        TargetKind[] kinds = {
            logs(),
            new TargetKind.Structure(
                    "minecraft:village",
                    StructureMarkers.get("minecraft:village").orElseThrow().markers(),
                    1,
                    64),
            new TargetKind.Biome("minecraft:forest", 64),
            new TargetKind.Entity("minecraft:villager", 32),
            new TargetKind.Place("Home", "minecraft:overworld", 32)
        };
        for (TargetKind kind : kinds) {
            TargetKind restored = TargetKind.parse(kind.toJson());
            assertEquals(kind.kind(), restored.kind());
            assertEquals(kind.coverageKey(), restored.coverageKey());
            assertEquals(kind.label(), restored.label());
            assertEquals(kind.dimensions(), restored.dimensions());
        }
    }

    @Test
    void unknownKindIsRejectedWithItsName() {
        JsonObject json = new JsonObject();
        json.addProperty("kind", "treasure_map");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> TargetKind.parse(json));
        assertTrue(error.getMessage().contains("treasure_map"));
    }

    @Test
    void uncataloguedStructureIsUnknown() {
        assertTrue(StructureMarkers.get("minecraft:doom_castle").isEmpty());
        assertTrue(StructureMarkers.get("village").isPresent());
        assertTrue(StructureMarkers.get("").isEmpty());
    }
}
