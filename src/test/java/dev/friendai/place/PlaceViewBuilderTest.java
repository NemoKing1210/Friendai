package dev.friendai.place;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.config.ServerConfig;
import dev.friendai.features.navigation.pathing.Pos;
import dev.friendai.memory.MenuStatus;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlaceViewBuilderTest {
    private static final String OVERWORLD = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";

    private static PlaceValues values(String name, PlaceKind kind, Pos anchor, String dimension) {
        return new PlaceValues(
                name,
                kind,
                "",
                dimension,
                anchor,
                8,
                Set.of(),
                PlaceSource.COMMAND,
                UUID.fromString("00000000-0000-0000-0000-0000000000a1"),
                "note for " + name);
    }

    private static PlaceRegistry registryWithOnePlace() {
        PlaceRegistry registry = new PlaceRegistry(PlaceLimits.DEFAULTS);
        registry.remember(values("home", PlaceKind.HOUSE, new Pos(10, 64, -20), OVERWORLD), 100);
        return registry;
    }

    private static PlaceRegistry registryWithPlaces(int count) {
        PlaceRegistry registry = new PlaceRegistry(PlaceLimits.DEFAULTS);
        for (int i = 0; i < count; i++) {
            registry.remember(values("place" + i, PlaceKind.MINE, new Pos(i, 40, -i), OVERWORLD), 100 + i);
        }
        return registry;
    }

    @Test
    void mapsEveryStoredFieldIntoARow() {
        PlaceView view =
                PlaceViewBuilder.build(registryWithOnePlace(), ServerConfig.defaults().places, Optional.empty());
        assertTrue(view.enabled());
        assertEquals(1, view.places().size());
        PlaceView.Row row = view.places().get(0);
        assertEquals(1, row.id());
        assertEquals("home", row.name());
        assertEquals("friendai.menu.place.kind.house", row.kindKey());
        assertEquals("", row.label());
        assertEquals(OVERWORLD, row.dimension());
        assertEquals(10, row.x());
        assertEquals(64, row.y());
        assertEquals(-20, row.z());
        assertEquals(8, row.radius());
        assertEquals(0, row.visits());
        assertEquals(0, row.lastVisitTick());
        assertEquals("friendai.menu.place.source.command", row.sourceKey());
        assertEquals("note for home", row.note());
    }

    @Test
    void rowsSortByUpdatedTickDescendingThenName() {
        PlaceRegistry registry = new PlaceRegistry(PlaceLimits.DEFAULTS);
        registry.remember(values("beta", PlaceKind.HOUSE, new Pos(0, 64, 0), OVERWORLD), 10);
        registry.remember(values("alpha", PlaceKind.FARM, new Pos(4, 64, 4), OVERWORLD), 30);
        registry.remember(values("gamma", PlaceKind.MINE, new Pos(8, 40, 8), OVERWORLD), 30);
        PlaceView view = PlaceViewBuilder.build(registry, ServerConfig.defaults().places, Optional.empty());
        assertEquals(
                List.of("alpha", "gamma", "beta"),
                view.places().stream().map(PlaceView.Row::name).toList());
    }

    @Test
    void kindAndSourceKeysUseTheMenuTranslations() {
        for (PlaceKind kind : PlaceKind.values()) {
            PlaceRegistry registry = new PlaceRegistry(PlaceLimits.DEFAULTS);
            registry.remember(values("only", kind, new Pos(0, 64, 0), OVERWORLD), 1);
            PlaceView view = PlaceViewBuilder.build(registry, ServerConfig.defaults().places, Optional.empty());
            assertEquals(
                    "friendai.menu.place.kind." + kind.name().toLowerCase(java.util.Locale.ROOT),
                    view.places().get(0).kindKey());
        }

        PlaceRegistry registry = new PlaceRegistry(PlaceLimits.DEFAULTS);
        registry.remember(
                new PlaceValues(
                        "built",
                        PlaceKind.HOUSE,
                        "",
                        OVERWORLD,
                        new Pos(0, 64, 0),
                        8,
                        Set.of(),
                        PlaceSource.BUILD,
                        UUID.randomUUID(),
                        ""),
                1);
        PlaceView view = PlaceViewBuilder.build(registry, ServerConfig.defaults().places, Optional.empty());
        assertEquals("friendai.menu.place.source.build", view.places().get(0).sourceKey());
    }

    @Test
    void enabledComesFromTheConfigAndStatusPassesThrough() {
        ServerConfig.PlacesSection section = new ServerConfig.PlacesSection();
        section.enabled = false;
        MenuStatus status = MenuStatus.ok("friendai.menu.places.status.forgotten", "home");
        PlaceView view = PlaceViewBuilder.build(registryWithOnePlace(), section, Optional.of(status));
        assertFalse(view.enabled());
        assertEquals(Optional.of(status), view.status());
    }

    @Test
    void rowsAreCappedAtTheCodecLimit() {
        PlaceView view = PlaceViewBuilder.build(
                registryWithPlaces(PlaceViewCodec.MAX_PLACES + 20), ServerConfig.defaults().places, Optional.empty());
        assertEquals(PlaceViewCodec.MAX_PLACES, view.places().size());
    }

    @Test
    void anEmptyRegistryStillCarriesTheEnabledFlag() {
        PlaceView view = PlaceViewBuilder.build(
                new PlaceRegistry(PlaceLimits.DEFAULTS), ServerConfig.defaults().places, Optional.empty());
        assertTrue(view.places().isEmpty());
        assertTrue(view.status().isEmpty());
    }

    @Test
    void aPlaceInAnotherDimensionKeepsItsOwnDimension() {
        PlaceRegistry registry = new PlaceRegistry(PlaceLimits.DEFAULTS);
        registry.remember(values("portal", PlaceKind.PORTAL, new Pos(0, 64, 0), OVERWORLD), 1);
        registry.remember(values("portal", PlaceKind.PORTAL, new Pos(0, 64, 0), NETHER), 2);
        PlaceView view = PlaceViewBuilder.build(registry, ServerConfig.defaults().places, Optional.empty());
        assertEquals(
                List.of(NETHER, OVERWORLD),
                view.places().stream().map(PlaceView.Row::dimension).toList());
    }
}
