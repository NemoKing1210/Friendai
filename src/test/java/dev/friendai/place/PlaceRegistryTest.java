package dev.friendai.place;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.pathing.Pos;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlaceRegistryTest {
    private static final String OVERWORLD = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";

    @Test
    void rememberUpdatesTheSameNameInPlace() {
        PlaceRegistry registry = new PlaceRegistry(PlaceLimits.DEFAULTS);
        Place first = registry.remember(values("home", PlaceKind.HOUSE, new Pos(0, 64, 0)), 10);
        Place moved = registry.remember(values("HOME", PlaceKind.HOUSE, new Pos(40, 64, 0)), 20);
        assertEquals(first.id(), moved.id());
        assertEquals(new Pos(40, 64, 0), moved.anchor());
        assertEquals(10, moved.createdTick());
        assertEquals(20, moved.updatedTick());
    }

    @Test
    void aliasCollisionsNameTheOtherPlace() {
        PlaceRegistry registry = new PlaceRegistry(PlaceLimits.DEFAULTS);
        registry.remember(values("home", PlaceKind.HOUSE, new Pos(0, 64, 0)), 1);
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> registry.remember(values("база", PlaceKind.HOUSE, new Pos(5, 64, 0), Set.of("home")), 2));
        assertTrue(error.getMessage().contains("home"));
    }

    @Test
    void theCapFailsCreationButAllowsUpdates() {
        PlaceRegistry registry = new PlaceRegistry(new PlaceLimits(1, 32, 4, 160, 1 << 20));
        registry.remember(values("home", PlaceKind.HOUSE, new Pos(0, 64, 0)), 1);
        assertEquals(
                "home", registry.remember(values("home", PlaceKind.HOUSE, new Pos(1, 64, 0)), 2).name());
        assertThrows(
                IllegalArgumentException.class,
                () -> registry.remember(values("mine", PlaceKind.MINE, new Pos(9, 40, 9)), 3));
    }

    @Test
    void idsAreMonotonicAndNotReusedAfterRemove() {
        PlaceRegistry registry = new PlaceRegistry(PlaceLimits.DEFAULTS);
        Place first = registry.remember(values("home", PlaceKind.HOUSE, new Pos(0, 64, 0)), 1);
        Place second = registry.remember(values("mine", PlaceKind.MINE, new Pos(5, 40, 5)), 2);
        assertTrue(second.id() > first.id());
        assertTrue(registry.remove(second.id()));
        assertFalse(registry.remove(second.id()));
        assertTrue(registry.byId(second.id()).isEmpty());
        Place third = registry.remember(values("farm", PlaceKind.FARM, new Pos(9, 64, 9)), 3);
        assertEquals(second.id() + 1, third.id());
    }

    @Test
    void sameNameIsAllowedInTwoDimensions() {
        PlaceRegistry registry = new PlaceRegistry(PlaceLimits.DEFAULTS);
        Place overworld = registry.remember(values("portal", PlaceKind.PORTAL, new Pos(0, 64, 0)), 1);
        Place nether =
                registry.remember(values("portal", PlaceKind.PORTAL, new Pos(0, 64, 0), Set.of(), NETHER), 2);
        assertNotEquals(overworld.id(), nether.id());
        assertEquals(NETHER, nether.dimension());
        assertEquals(2, registry.all().size());
        assertEquals(overworld.id(), registry.find("portal", OVERWORLD).orElseThrow().id());
        assertEquals(nether.id(), registry.find("PORTAL", NETHER).orElseThrow().id());
    }

    @Test
    void visitCountsAndStampsTheLastVisit() {
        PlaceRegistry registry = new PlaceRegistry(PlaceLimits.DEFAULTS);
        Place home = registry.remember(values("home", PlaceKind.HOUSE, new Pos(0, 64, 0)), 10);
        Place visited = registry.visit(home.id(), 42);
        assertEquals(1, visited.visits());
        assertEquals(42, visited.lastVisitTick());
        assertEquals(10, visited.updatedTick());
        assertEquals(2, registry.visit(home.id(), 50).visits());
        assertThrows(IllegalArgumentException.class, () -> registry.visit(999, 1));
    }

    @Test
    void renameRejectsCollisionsAndRebuildsKeys() {
        PlaceRegistry registry = new PlaceRegistry(PlaceLimits.DEFAULTS);
        Place home = registry.remember(values("home", PlaceKind.HOUSE, new Pos(0, 64, 0), Set.of("house")), 1);
        registry.remember(values("mine", PlaceKind.MINE, new Pos(5, 40, 5)), 2);
        IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, () -> registry.rename(home.id(), "MINE", 3));
        assertTrue(error.getMessage().contains("mine"));

        Place renamed = registry.rename(home.id(), "  Дом  ", 4);
        assertEquals("дом", renamed.name());
        assertEquals(Set.of("house"), renamed.aliases());
        assertEquals(4, renamed.updatedTick());
        assertEquals(home.id(), registry.find("дом", OVERWORLD).orElseThrow().id());
        assertEquals(home.id(), registry.find("house", OVERWORLD).orElseThrow().id());
        assertTrue(registry.find("home", OVERWORLD).isEmpty());
    }

    @Test
    void rememberUnionsAliasesAndFindsByThem() {
        PlaceRegistry registry = new PlaceRegistry(PlaceLimits.DEFAULTS);
        Place home = registry.remember(values("home", PlaceKind.HOUSE, new Pos(0, 64, 0), Set.of("house")), 1);
        Place merged = registry.remember(values("HOUSE", PlaceKind.HOUSE, new Pos(8, 64, 0), Set.of("base")), 2);
        assertEquals(home.id(), merged.id());
        assertEquals("home", merged.name());
        assertEquals(Set.of("house", "base"), merged.aliases());
        assertEquals(1, registry.all().size());
        assertEquals(home.id(), registry.find("base", OVERWORLD).orElseThrow().id());
    }

    @Test
    void updateKeepsTheIdAuthorAndVisits() {
        PlaceRegistry registry = new PlaceRegistry(PlaceLimits.DEFAULTS);
        UUID author = UUID.fromString("00000000-0000-0000-0000-00000000000a");
        PlaceValues taught = new PlaceValues(
                "home", PlaceKind.HOUSE, "", OVERWORLD, new Pos(0, 64, 0), 8, Set.of(), PlaceSource.COMMAND,
                author, "by the door");
        Place first = registry.remember(taught, 1);
        registry.visit(first.id(), 2);
        Place updated = registry.remember(values("home", PlaceKind.HOUSE, new Pos(1, 64, 1)), 3);
        assertEquals(first.id(), updated.id());
        assertEquals(1, updated.visits());
        assertEquals(2, updated.lastVisitTick());
        assertEquals(author, updated.author());
        assertEquals(PlaceSource.COMMAND, updated.source());
        assertEquals(1, updated.createdTick());
    }

    @Test
    void revisionChangesOnlyWhenTheRegistryChanges() {
        PlaceRegistry registry = new PlaceRegistry(PlaceLimits.DEFAULTS);
        assertEquals(0, registry.revision());
        registry.remember(values("home", PlaceKind.HOUSE, new Pos(0, 64, 0)), 1);
        long afterCreate = registry.revision();
        assertTrue(afterCreate > 0);
        registry.remember(values("home", PlaceKind.HOUSE, new Pos(0, 64, 0)), 1);
        assertEquals(afterCreate, registry.revision());
        registry.remember(values("home", PlaceKind.HOUSE, new Pos(0, 64, 0)), 5);
        assertTrue(registry.revision() > afterCreate);
        registry.rename(registry.all().get(0).id(), "дом", 6);
        long afterRename = registry.revision();
        registry.rename(registry.all().get(0).id(), "ДОМ", 7);
        assertEquals(afterRename, registry.revision());
    }

    @Test
    void allIsSortedById() {
        PlaceRegistry registry = new PlaceRegistry(PlaceLimits.DEFAULTS);
        Place first = registry.remember(values("home", PlaceKind.HOUSE, new Pos(0, 64, 0)), 1);
        Place second = registry.remember(values("mine", PlaceKind.MINE, new Pos(5, 40, 5)), 2);
        Place third = registry.remember(values("farm", PlaceKind.FARM, new Pos(9, 64, 9)), 3);
        assertEquals(List.of(first, second, third), registry.all());
    }

    @Test
    void findNeedsAnExactNameOrAlias() {
        PlaceRegistry registry = new PlaceRegistry(PlaceLimits.DEFAULTS);
        registry.remember(values("home", PlaceKind.HOUSE, new Pos(0, 64, 0), Set.of("дом")), 1);
        assertTrue(registry.find("ho", OVERWORLD).isEmpty());
        assertTrue(registry.find("дом", OVERWORLD).isPresent());
        assertTrue(registry.find(null, OVERWORLD).isEmpty());
        assertTrue(registry.find("home", " ").isEmpty());
    }

    @Test
    void thePlaceRecordEnforcesItsBounds() {
        Place normalized = place(1, "  Дом ", PlaceKind.HOUSE, new Pos(0, 64, 0));
        assertEquals("дом", normalized.name());

        assertThrows(IllegalArgumentException.class, () -> place(0, "home", PlaceKind.HOUSE, new Pos(0, 64, 0)));
        assertThrows(IllegalArgumentException.class, () -> place(1, "   ", PlaceKind.HOUSE, new Pos(0, 64, 0)));
        assertThrows(IllegalArgumentException.class, () -> place(1, "x".repeat(33), PlaceKind.HOUSE, new Pos(0, 64, 0)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new Place(
                        1, "home", PlaceKind.HOUSE, "", OVERWORLD, new Pos(0, 64, 0), 0, Set.of(),
                        PlaceSource.MANUAL, null, 0, 0, 0, 0, ""));
        assertThrows(
                IllegalArgumentException.class,
                () -> new Place(
                        1, "home", PlaceKind.HOUSE, "", OVERWORLD, new Pos(0, 64, 0), 65, Set.of(),
                        PlaceSource.MANUAL, null, 0, 0, 0, 0, ""));
        assertThrows(
                IllegalArgumentException.class,
                () -> new Place(
                        1, "home", PlaceKind.HOUSE, "", OVERWORLD, new Pos(30_000_001, 64, 0), 8, Set.of(),
                        PlaceSource.MANUAL, null, 0, 0, 0, 0, ""));
        assertThrows(
                IllegalArgumentException.class,
                () -> new Place(
                        1, "home", PlaceKind.HOUSE, "", " ", new Pos(0, 64, 0), 8, Set.of(),
                        PlaceSource.MANUAL, null, 0, 0, 0, 0, ""));
        assertThrows(
                IllegalArgumentException.class,
                () -> new Place(
                        1, "home", PlaceKind.HOUSE, "", OVERWORLD, new Pos(0, 64, 0), 8,
                        Set.of("a", "b", "c", "d", "e"), PlaceSource.MANUAL, null, 0, 0, 0, 0, ""));
        assertThrows(
                IllegalArgumentException.class,
                () -> new Place(
                        1, "home", PlaceKind.HOUSE, "", OVERWORLD, new Pos(0, 64, 0), 8,
                        Set.of("x".repeat(33)), PlaceSource.MANUAL, null, 0, 0, 0, 0, ""));
        assertThrows(
                IllegalArgumentException.class,
                () -> new Place(
                        1, "home", PlaceKind.HOUSE, "", OVERWORLD, new Pos(0, 64, 0), 8, Set.of(),
                        PlaceSource.MANUAL, null, 0, 0, 0, 0, "x".repeat(161)));
        Place aliased = new Place(
                2, "house", PlaceKind.HOUSE, "", OVERWORLD, new Pos(0, 64, 0), 8, Set.of("HOME"),
                PlaceSource.MANUAL, null, 0, 0, 0, 0, "");
        assertEquals(Set.of("home"), aliased.aliases());
    }

    private static PlaceValues values(String name, PlaceKind kind, Pos pos) {
        return values(name, kind, pos, Set.of());
    }

    private static PlaceValues values(String name, PlaceKind kind, Pos pos, Set<String> aliases) {
        return values(name, kind, pos, aliases, OVERWORLD);
    }

    private static PlaceValues values(
            String name, PlaceKind kind, Pos pos, Set<String> aliases, String dimension) {
        return new PlaceValues(
                name, kind, "", dimension, pos, 8, aliases, PlaceSource.MANUAL, null, "");
    }

    private static Place place(long id, String name, PlaceKind kind, Pos pos) {
        return place(id, name, kind, pos, OVERWORLD);
    }

    private static Place place(long id, String name, PlaceKind kind, Pos pos, String dimension) {
        return new Place(
                id, name, kind, "", dimension, pos, 8, Set.of(), PlaceSource.MANUAL, null, 0, 0, 0, 0, "");
    }
}
