package dev.friendai.features.places;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.features.navigation.pathing.Pos;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PlaceQueryTest {
    private static final String OVERWORLD = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";
    private static final Pos SELF = new Pos(0, 64, 0);

    @Test
    void sameDimensionResultsAreNearestFirst() {
        PlaceRegistry registry = registryWith(
                place(9, "near", PlaceKind.HOUSE, new Pos(3, 64, 4)),
                place(2, "far", PlaceKind.HOUSE, new Pos(10, 64, 0)));
        assertEquals(List.of(9L, 2L), ids(find(registry, request(5, false), SELF)));
    }

    @Test
    void equalDistancesBreakById() {
        PlaceRegistry registry = registryWith(
                place(8, "eight", PlaceKind.HOUSE, new Pos(0, 64, 5)),
                place(3, "three", PlaceKind.HOUSE, new Pos(5, 64, 0)));
        assertEquals(List.of(3L, 8L), ids(find(registry, request(5, false), SELF)));
    }

    @Test
    void otherDimensionHasNoDistance() {
        Place nether = place(1, "portal", PlaceKind.PORTAL, new Pos(0, 64, 0), NETHER);
        PlaceQuery.Result result = PlaceQuery.find(
                        registryWith(nether),
                        new PlaceQuery.Request(Optional.empty(), Set.of(), Optional.empty(), 5, true),
                        new Pos(0, 64, 0),
                        "minecraft:overworld")
                .getFirst();
        assertFalse(result.sameDimension());
        assertEquals(0.0, result.distance(), 0.0001);
    }

    @Test
    void otherDimensionsSortByRecency() {
        PlaceRegistry registry = registryWith(
                placeAt(1, "older", NETHER, new Pos(0, 64, 0), 5), placeAt(2, "newer", NETHER, new Pos(30, 64, 0), 9));
        List<PlaceQuery.Result> results = find(registry, request(5, true), SELF);
        assertEquals(List.of(2L, 1L), ids(results));
        assertEquals(
                List.of(false, false),
                results.stream().map(PlaceQuery.Result::sameDimension).toList());
    }

    @Test
    void textMatchesNameAliasAndLabel() {
        Place home = new Place(
                1,
                "дом",
                PlaceKind.HOUSE,
                "",
                OVERWORLD,
                new Pos(0, 64, 0),
                8,
                Set.of("home"),
                PlaceSource.MANUAL,
                null,
                0,
                0,
                0,
                0,
                "");
        Place field = new Place(
                2,
                "north field",
                PlaceKind.FARM,
                "wheat",
                OVERWORLD,
                new Pos(30, 64, 0),
                8,
                Set.of(),
                PlaceSource.FARM,
                null,
                0,
                0,
                0,
                0,
                "");
        PlaceRegistry registry = registryWith(home, field);
        assertEquals(List.of(1L), ids(find(registry, textRequest("home"), SELF)));
        assertEquals(List.of(1L), ids(find(registry, textRequest("дом"), SELF)));
        assertEquals(List.of(2L), ids(find(registry, textRequest("wheat"), SELF)));
    }

    @Test
    void prefixMatchesWinOverSubstringMatches() {
        PlaceRegistry registry = registryWith(
                place(1, "mineshaft", PlaceKind.MINE, new Pos(5, 40, 0)),
                place(2, "old mine", PlaceKind.MINE, new Pos(9, 40, 0)));
        assertEquals(List.of(1L), ids(find(registry, textRequest("mine"), SELF)));
    }

    @Test
    void substringMatchesWhenNoPrefixMatches() {
        PlaceRegistry registry = registryWith(place(2, "north field", PlaceKind.FARM, new Pos(5, 64, 0)));
        assertEquals(List.of(2L), ids(find(registry, textRequest("field"), SELF)));
    }

    @Test
    void kindFilterKeepsOnlyRequestedKinds() {
        PlaceRegistry registry = registryWith(
                place(1, "home", PlaceKind.HOUSE, new Pos(0, 64, 0)),
                place(2, "mine", PlaceKind.MINE, new Pos(30, 40, 0)));
        PlaceQuery.Request request =
                new PlaceQuery.Request(Optional.empty(), Set.of(PlaceKind.MINE), Optional.empty(), 5, false);
        assertEquals(List.of(2L), ids(find(registry, request, SELF)));
    }

    @Test
    void dimensionFilterIsExact() {
        PlaceRegistry registry = registryWith(
                place(1, "home", PlaceKind.HOUSE, new Pos(0, 64, 0)),
                place(2, "portal", PlaceKind.PORTAL, new Pos(30, 64, 0), NETHER));
        PlaceQuery.Request request = new PlaceQuery.Request(Optional.empty(), Set.of(), Optional.of(NETHER), 5, true);
        assertEquals(List.of(2L), ids(find(registry, request, SELF)));
    }

    @Test
    void otherDimensionsFalseKeepsOnlySelfDimension() {
        PlaceRegistry registry = registryWith(
                place(1, "home", PlaceKind.HOUSE, new Pos(0, 64, 0)),
                place(2, "portal", PlaceKind.PORTAL, new Pos(30, 64, 0), NETHER));
        assertEquals(List.of(1L), ids(find(registry, request(5, false), SELF)));
    }

    @Test
    void otherDimensionsAreAppendedAfterSameDimension() {
        PlaceRegistry registry = registryWith(
                placeAt(1, "portal", NETHER, new Pos(1, 64, 1), 99),
                place(2, "home", PlaceKind.HOUSE, new Pos(50, 64, 0)));
        List<PlaceQuery.Result> results = find(registry, request(5, true), SELF);
        assertEquals(List.of(2L, 1L), ids(results));
        assertTrue(results.getFirst().sameDimension());
    }

    @Test
    void limitIsClampedToTheRange() {
        List<Place> many = new ArrayList<>();
        for (int i = 1; i <= 25; i++) {
            many.add(place(i, "place " + i, PlaceKind.LANDMARK, new Pos(i * 10, 64, 0)));
        }
        PlaceRegistry registry = registryWith(many.toArray(Place[]::new));
        assertEquals(20, find(registry, request(100, false), SELF).size());
        assertEquals(1, find(registry, request(0, false), SELF).size());
        assertEquals(1, find(registry, request(-4, false), SELF).size());
        assertEquals(5, find(registry, request(5, false), SELF).size());
    }

    @Test
    void noMatchIsEmpty() {
        PlaceRegistry registry = registryWith(place(1, "home", PlaceKind.HOUSE, new Pos(0, 64, 0)));
        assertTrue(find(registry, textRequest("nothing"), SELF).isEmpty());
    }

    @Test
    void textWithControlCharactersMatchesNothing() {
        PlaceRegistry registry = registryWith(place(1, "home", PlaceKind.HOUSE, new Pos(0, 64, 0)));
        assertTrue(find(registry, textRequest("a\nb"), SELF).isEmpty());
    }

    private static List<PlaceQuery.Result> find(PlaceRegistry registry, PlaceQuery.Request request, Pos self) {
        return PlaceQuery.find(registry, request, self, OVERWORLD);
    }

    private static PlaceQuery.Request request(int limit, boolean otherDimensions) {
        return new PlaceQuery.Request(Optional.empty(), Set.of(), Optional.empty(), limit, otherDimensions);
    }

    private static PlaceQuery.Request textRequest(String text) {
        return new PlaceQuery.Request(Optional.of(text), Set.of(), Optional.empty(), 5, false);
    }

    private static List<Long> ids(List<PlaceQuery.Result> results) {
        return results.stream().map(result -> result.place().id()).toList();
    }

    private static PlaceRegistry registryWith(Place... places) {
        long nextId = 1;
        List<PlaceFile.Record> records = new ArrayList<>(places.length);
        for (Place place : places) {
            records.add(PlaceFile.Record.of(place));
            nextId = Math.max(nextId, place.id() + 1);
        }
        return PlaceRegistry.restore(new PlaceFile(nextId, records), PlaceLimits.DEFAULTS);
    }

    private static Place place(long id, String name, PlaceKind kind, Pos pos) {
        return place(id, name, kind, pos, OVERWORLD);
    }

    private static Place place(long id, String name, PlaceKind kind, Pos pos, String dimension) {
        return new Place(id, name, kind, "", dimension, pos, 8, Set.of(), PlaceSource.MANUAL, null, 0, 0, 0, 0, "");
    }

    private static Place placeAt(long id, String name, String dimension, Pos pos, long updatedTick) {
        return new Place(
                id,
                name,
                PlaceKind.PORTAL,
                "",
                dimension,
                pos,
                8,
                Set.of(),
                PlaceSource.MANUAL,
                null,
                0,
                updatedTick,
                0,
                0,
                "");
    }
}
