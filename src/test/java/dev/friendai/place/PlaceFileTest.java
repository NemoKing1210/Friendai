package dev.friendai.place;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.pathing.Pos;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlaceFileTest {
    private static final String OVERWORLD = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";

    @Test
    void roundTripKeepsIdsAliasesAndTicks() {
        PlaceRegistry registry = new PlaceRegistry(PlaceLimits.DEFAULTS);
        Place home = registry.remember(values("дом", PlaceKind.HOUSE, new Pos(1, 64, 2), Set.of("home")), 7);
        PlaceRegistry restored = PlaceFile.decode(PlaceFile.encode(registry), PlaceLimits.DEFAULTS);
        Place copy = restored.byId(home.id()).orElseThrow();
        assertEquals("дом", copy.name());
        assertEquals(Set.of("home"), copy.aliases());
        assertEquals(7, copy.createdTick());
    }

    @Test
    void unknownVersionIsRejected() {
        String json = "{\"version\":3,\"nextId\":1,\"places\":[]}";
        assertThrows(IllegalArgumentException.class, () -> PlaceFile.decode(json, PlaceLimits.DEFAULTS));
    }

    @Test
    void aVersionOneFileGetsTheDefaultBand() {
        String json = "{\"version\":1,\"nextId\":2,\"places\":[" + placeJson(1, "home", OVERWORLD) + "]}";
        Place home = PlaceFile.decode(json, PlaceLimits.DEFAULTS).all().getFirst();
        assertEquals(1, home.below());
        assertEquals(8, home.above());
    }

    @Test
    void anExplicitBandRoundTrips() {
        PlaceRegistry registry = new PlaceRegistry(PlaceLimits.DEFAULTS);
        registry.remember(new PlaceValues("mine", PlaceKind.MINE, "", OVERWORLD, new Pos(4, 40, 4), 6, Set.of(),
                PlaceSource.MANUAL, null, "", 16, 2), 3);
        String json = PlaceFile.encode(registry).toJson();
        assertTrue(json.contains("\"below\": 16"), json);
        assertTrue(json.contains("\"above\": 2"), json);
        Place copy = PlaceFile.decode(json, PlaceLimits.DEFAULTS).all().getFirst();
        assertEquals(16, copy.below());
        assertEquals(2, copy.above());
    }

    @Test
    void duplicateNamePerDimensionIsRejected() {
        String json = "{\"version\":1,\"nextId\":3,\"places\":["
                + placeJson(1, "home", "minecraft:overworld") + "," + placeJson(2, "HOME", "minecraft:overworld") + "]}";
        assertThrows(IllegalArgumentException.class, () -> PlaceFile.decode(json, PlaceLimits.DEFAULTS));
    }

    @Test
    void duplicateAliasPerDimensionIsRejected() {
        String json = "{\"version\":1,\"nextId\":3,\"places\":["
                + placeJson(1, "home", OVERWORLD, "[\"base\"]") + ","
                + placeJson(2, "base", OVERWORLD) + "]}";
        assertThrows(IllegalArgumentException.class, () -> PlaceFile.decode(json, PlaceLimits.DEFAULTS));
    }

    @Test
    void theSameNameInAnotherDimensionIsKept() {
        String json = "{\"version\":1,\"nextId\":3,\"places\":["
                + placeJson(1, "home", OVERWORLD) + "," + placeJson(2, "home", NETHER) + "]}";
        PlaceRegistry restored = PlaceFile.decode(json, PlaceLimits.DEFAULTS);
        assertEquals(2, restored.all().size());
        assertTrue(restored.find("home", OVERWORLD).isPresent());
        assertTrue(restored.find("home", NETHER).isPresent());
    }

    @Test
    void nextIdMustExceedEveryStoredId() {
        String json = "{\"version\":1,\"nextId\":2,\"places\":[" + placeJson(2, "home", OVERWORLD) + "]}";
        assertThrows(IllegalArgumentException.class, () -> PlaceFile.decode(json, PlaceLimits.DEFAULTS));
    }

    @Test
    void duplicateIdsAreRejected() {
        String json = "{\"version\":1,\"nextId\":3,\"places\":["
                + placeJson(1, "home", OVERWORLD) + "," + placeJson(1, "mine", OVERWORLD) + "]}";
        assertThrows(IllegalArgumentException.class, () -> PlaceFile.decode(json, PlaceLimits.DEFAULTS));
    }

    @Test
    void restoreContinuesAfterTheStoredNextId() {
        String json = "{\"version\":1,\"nextId\":9,\"places\":[" + placeJson(2, "home", OVERWORLD) + "]}";
        PlaceRegistry restored = PlaceFile.decode(json, PlaceLimits.DEFAULTS);
        assertEquals(2, restored.all().getFirst().id());
        assertEquals(9, restored.remember(values("mine", PlaceKind.MINE, new Pos(5, 40, 5)), 9).id());
    }

    @Test
    void oversizedInputIsRejectedBeforeParsing() {
        String json = "{\"version\":1,\"nextId\":1,\"places\":[]}";
        PlaceLimits tiny = new PlaceLimits(8, 32, 4, 160, 8);
        assertThrows(IllegalArgumentException.class, () -> PlaceFile.decode(json, tiny));
    }

    @Test
    void anEmptyObjectIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> PlaceFile.decode("{}", PlaceLimits.DEFAULTS));
    }

    @Test
    void ticksMustBeOrderedAndNonNegative() {
        String backwards = "{\"version\":1,\"nextId\":3,\"places\":["
                + placeJson(1, "home", OVERWORLD).replace("\"created\":1,\"updated\":1", "\"created\":5,\"updated\":4")
                + "]}";
        assertThrows(IllegalArgumentException.class, () -> PlaceFile.decode(backwards, PlaceLimits.DEFAULTS));
        String negative = "{\"version\":1,\"nextId\":3,\"places\":["
                + placeJson(1, "home", OVERWORLD).replace("\"created\":1", "\"created\":-1")
                + "]}";
        assertThrows(IllegalArgumentException.class, () -> PlaceFile.decode(negative, PlaceLimits.DEFAULTS));
    }

    @Test
    void radiusOutsideOneToSixtyFourIsRejected() {
        String json = "{\"version\":1,\"nextId\":3,\"places\":["
                + placeJson(1, "home", OVERWORLD).replace("\"radius\":8", "\"radius\":65") + "]}";
        assertThrows(IllegalArgumentException.class, () -> PlaceFile.decode(json, PlaceLimits.DEFAULTS));
    }

    @Test
    void unknownKindOrSourceIsRejected() {
        String kind = "{\"version\":1,\"nextId\":3,\"places\":["
                + placeJson(1, "home", OVERWORLD).replace("\"kind\":\"HOUSE\"", "\"kind\":\"CASTLE\"") + "]}";
        assertThrows(IllegalArgumentException.class, () -> PlaceFile.decode(kind, PlaceLimits.DEFAULTS));
        String source = "{\"version\":1,\"nextId\":3,\"places\":["
                + placeJson(1, "home", OVERWORLD).replace("\"source\":\"MANUAL\"", "\"source\":\"MAGIC\"") + "]}";
        assertThrows(IllegalArgumentException.class, () -> PlaceFile.decode(source, PlaceLimits.DEFAULTS));
    }

    @Test
    void everyStoredFieldSurvivesTheRoundTrip() {
        UUID author = UUID.randomUUID();
        PlaceRegistry registry = new PlaceRegistry(PlaceLimits.DEFAULTS);
        Place place = registry.remember(new PlaceValues(
                "Wheat Farm", PlaceKind.FARM, "wheat", OVERWORLD, new Pos(120, 64, -30), 12,
                Set.of("farm", "пшеница"), PlaceSource.BUILD, author, "two chests inside"), 12_000);
        registry.visit(place.id(), 13_800);
        PlaceRegistry restored = PlaceFile.decode(PlaceFile.encode(registry), PlaceLimits.DEFAULTS);
        assertEquals(registry.all(), restored.all());
    }

    @Test
    void malformedJsonIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> PlaceFile.decode("{not json}", PlaceLimits.DEFAULTS));
        assertThrows(IllegalArgumentException.class,
                () -> PlaceFile.decode("[1,2,3]", PlaceLimits.DEFAULTS));
    }

    @Test
    void theWrittenFileKeepsTheDocumentedShape() {
        PlaceRegistry registry = new PlaceRegistry(PlaceLimits.DEFAULTS);
        registry.remember(values("home", PlaceKind.HOUSE, new Pos(1, 64, 2), Set.of("дом")), 7);
        String json = PlaceFile.encode(registry).toJson();
        assertTrue(json.contains("\"version\": 2"));
        assertTrue(json.contains("\"nextId\": 2"));
        assertTrue(json.contains("\"places\": ["));
        assertTrue(json.contains("\"kind\": \"HOUSE\""));
        assertTrue(json.contains("\"source\": \"MANUAL\""));
        assertTrue(json.contains("\"aliases\": ["));
        assertTrue(json.contains("\"below\": 1"));
        assertTrue(json.contains("\"above\": 8"));
        assertTrue(json.contains("\"lastVisit\": 0"));
        assertTrue(json.contains("\"created\": 7"));
    }

    @Test
    void morePlacesThanTheLimitAreRejected() {
        String json = "{\"version\":1,\"nextId\":3,\"places\":["
                + placeJson(1, "home", OVERWORLD) + "," + placeJson(2, "mine", OVERWORLD) + "]}";
        PlaceLimits one = new PlaceLimits(1, 32, 4, 160, 1 << 20);
        assertThrows(IllegalArgumentException.class, () -> PlaceFile.decode(json, one));
    }

    @Test
    void aLabelLongerThanTheBoundIsRejectedOnLoad() {
        String json = "{\"version\":1,\"nextId\":2,\"places\":["
                + placeJson(1, "home", OVERWORLD)
                        .replace("\"label\":\"\"", "\"label\":\"" + "x".repeat(PlaceLimits.LABEL_MAX + 1) + "\"")
                + "]}";
        assertThrows(IllegalArgumentException.class, () -> PlaceFile.decode(json, PlaceLimits.DEFAULTS));
    }

    private static PlaceValues values(String name, PlaceKind kind, Pos pos) {
        return values(name, kind, pos, Set.of());
    }

    private static PlaceValues values(String name, PlaceKind kind, Pos pos, Set<String> aliases) {
        return new PlaceValues(
                name, kind, "", OVERWORLD, pos, 8, aliases, PlaceSource.MANUAL, null, "");
    }

    private static String placeJson(long id, String name, String dimension) {
        return placeJson(id, name, dimension, "[]");
    }

    private static String placeJson(long id, String name, String dimension, String aliasesJson) {
        return "{\"id\":" + id + ",\"name\":\"" + name + "\",\"kind\":\"HOUSE\",\"label\":\"\",\"dimension\":\""
                + dimension + "\",\"x\":1,\"y\":64,\"z\":2,\"radius\":8,\"aliases\":" + aliasesJson
                + ",\"source\":\"MANUAL\",\"author\":null,\"created\":1,\"updated\":1,\"lastVisit\":0,\"visits\":0,"
                + "\"note\":\"\"}";
    }
}
