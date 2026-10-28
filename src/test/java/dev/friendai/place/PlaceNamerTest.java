package dev.friendai.place;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.pathing.Pos;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PlaceNamerTest {
    private static final String OVERWORLD = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";

    @Test
    void farmNamesUseTheLabel() {
        PlaceRegistry registry = new PlaceRegistry(PlaceLimits.DEFAULTS);
        assertEquals("wheat farm", PlaceNamer.autoName(registry, OVERWORLD, PlaceKind.FARM, "wheat"));
        registry.remember(values("wheat farm", PlaceKind.FARM, new Pos(0, 64, 0)), 1);
        assertEquals("wheat farm 2", PlaceNamer.autoName(registry, OVERWORLD, PlaceKind.FARM, "wheat"));
    }

    @Test
    void otherKindsUseTheirOwnBaseName() {
        PlaceRegistry registry = new PlaceRegistry(PlaceLimits.DEFAULTS);
        assertEquals("house", PlaceNamer.autoName(registry, OVERWORLD, PlaceKind.HOUSE, "wheat"));
        assertEquals("mine", PlaceNamer.autoName(registry, OVERWORLD, PlaceKind.MINE, "iron"));
        assertEquals("storage", PlaceNamer.autoName(registry, OVERWORLD, PlaceKind.STORAGE, ""));
        assertEquals("portal", PlaceNamer.autoName(registry, OVERWORLD, PlaceKind.PORTAL, ""));
        assertEquals("trading post", PlaceNamer.autoName(registry, OVERWORLD, PlaceKind.TRADE, ""));
        assertEquals("landmark", PlaceNamer.autoName(registry, OVERWORLD, PlaceKind.LANDMARK, ""));
    }

    @Test
    void farmWithoutLabelIsJustFarm() {
        PlaceRegistry registry = new PlaceRegistry(PlaceLimits.DEFAULTS);
        assertEquals("farm", PlaceNamer.autoName(registry, OVERWORLD, PlaceKind.FARM, ""));
        assertEquals("farm", PlaceNamer.autoName(registry, OVERWORLD, PlaceKind.FARM, "   "));
    }

    @Test
    void suffixesCountUpFromTwo() {
        PlaceRegistry registry = new PlaceRegistry(PlaceLimits.DEFAULTS);
        registry.remember(values("wheat farm", PlaceKind.FARM, new Pos(0, 64, 0)), 1);
        registry.remember(values("wheat farm 2", PlaceKind.FARM, new Pos(0, 64, 0)), 1);
        assertEquals("wheat farm 3", PlaceNamer.autoName(registry, OVERWORLD, PlaceKind.FARM, "wheat"));
    }

    @Test
    void anAliasAlsoBlocksTheName() {
        PlaceRegistry registry = new PlaceRegistry(PlaceLimits.DEFAULTS);
        registry.remember(
                new PlaceValues(
                        "base",
                        PlaceKind.HOUSE,
                        "",
                        OVERWORLD,
                        new Pos(0, 64, 0),
                        8,
                        Set.of("house"),
                        PlaceSource.MANUAL,
                        null,
                        ""),
                1);
        assertEquals("house 2", PlaceNamer.autoName(registry, OVERWORLD, PlaceKind.HOUSE, ""));
    }

    @Test
    void namesAreScopedToOneDimension() {
        PlaceRegistry registry = new PlaceRegistry(PlaceLimits.DEFAULTS);
        registry.remember(values("house", PlaceKind.HOUSE, new Pos(0, 64, 0), NETHER), 1);
        assertEquals("house", PlaceNamer.autoName(registry, OVERWORLD, PlaceKind.HOUSE, ""));
    }

    @Test
    void longBaseIsTruncatedToThirtyTwoChars() {
        PlaceRegistry registry = new PlaceRegistry(PlaceLimits.DEFAULTS);
        String name = PlaceNamer.autoName(registry, OVERWORLD, PlaceKind.FARM, "x".repeat(40));
        assertEquals(32, name.length());
        assertEquals("x".repeat(32), name);
    }

    @Test
    void suffixKeepsTheWholeNameWithinThirtyTwoChars() {
        PlaceRegistry registry = new PlaceRegistry(PlaceLimits.DEFAULTS);
        String longLabel = "x".repeat(40);
        registry.remember(values("x".repeat(32), PlaceKind.FARM, new Pos(0, 64, 0)), 1);
        String second = PlaceNamer.autoName(registry, OVERWORLD, PlaceKind.FARM, longLabel);
        assertEquals(32, second.length());
        assertTrue(second.endsWith(" 2"));
        assertEquals("x".repeat(30) + " 2", second);
    }

    @Test
    void exhaustedNamesThrow() {
        PlaceRegistry registry = new PlaceRegistry(PlaceLimits.DEFAULTS);
        registry.remember(values("wheat farm", PlaceKind.FARM, new Pos(0, 64, 0)), 1);
        for (int suffix = 2; suffix <= 999; suffix++) {
            registry.remember(values("wheat farm " + suffix, PlaceKind.FARM, new Pos(0, 64, 0)), 1);
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> PlaceNamer.autoName(registry, OVERWORLD, PlaceKind.FARM, "wheat"));
    }

    @Test
    void aBlankDimensionIsRejected() {
        PlaceRegistry registry = new PlaceRegistry(PlaceLimits.DEFAULTS);
        assertThrows(IllegalArgumentException.class, () -> PlaceNamer.autoName(registry, " ", PlaceKind.HOUSE, ""));
    }

    @Test
    void aControlCharacterLabelNamesNothing() {
        PlaceRegistry registry = new PlaceRegistry(PlaceLimits.DEFAULTS);
        assertEquals("farm", PlaceNamer.autoName(registry, OVERWORLD, PlaceKind.FARM, "bad\nlabel"));
    }

    private static PlaceValues values(String name, PlaceKind kind, Pos pos) {
        return values(name, kind, pos, OVERWORLD);
    }

    private static PlaceValues values(String name, PlaceKind kind, Pos pos, String dimension) {
        return new PlaceValues(name, kind, "", dimension, pos, 8, Set.of(), PlaceSource.MANUAL, null, "");
    }
}
