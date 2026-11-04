package dev.friendai.features.places;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.foundation.spatial.Pos;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlaceProtectionTest {
    private static final String OVERWORLD = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";
    private static final Pos HERE = new Pos(1, 64, 1);

    @Test
    void anAutoMineNeverBlocksFreeTime() {
        Place mine = place(1, "mine", PlaceKind.MINE, PlaceSource.MINE, new Pos(0, 64, 0), 32);

        assertTrue(PlaceProtection.blocker(List.of(mine), OVERWORLD, HERE, "fell_tree", true)
                .isEmpty());
    }

    @Test
    void anOwnerMineStillBlocks() {
        Place mine = place(1, "mine", PlaceKind.MINE, PlaceSource.COMMAND, new Pos(0, 64, 0), 8);

        assertEquals(Optional.of(mine), PlaceProtection.blocker(List.of(mine), OVERWORLD, HERE, "fell_tree", true));
    }

    @Test
    void anOwnedOrderIsNeverBlocked() {
        Place base = place(1, "base", PlaceKind.HOUSE, PlaceSource.COMMAND, new Pos(0, 64, 0), 8);

        assertTrue(PlaceProtection.blocker(List.of(base), OVERWORLD, HERE, "fell_tree", false)
                .isEmpty());
    }

    @Test
    void aPlaceInAnotherDimensionDoesNotBlock() {
        Place base = place(1, "base", PlaceKind.HOUSE, PlaceSource.COMMAND, new Pos(0, 64, 0), 8);

        assertTrue(PlaceProtection.blocker(List.of(base), NETHER, HERE, "fell_tree", true)
                .isEmpty());
    }

    @Test
    void theFarmExemptionAndTheStationExemptionSurvive() {
        Place farm = place(1, "farm", PlaceKind.FARM, PlaceSource.FARM, new Pos(0, 64, 0), 8);

        assertTrue(PlaceProtection.blocker(List.of(farm), OVERWORLD, HERE, "farm", true)
                .isEmpty());
        assertFalse(PlaceProtection.blocker(List.of(farm), OVERWORLD, HERE, "fell_tree", true)
                .isEmpty());
        assertTrue(PlaceProtection.blocker(List.of(farm), OVERWORLD, HERE, "craft_item", true)
                .isEmpty());
    }

    @Test
    void aBlankOrMissingPlaceListBlocksNothing() {
        assertTrue(PlaceProtection.blocker(List.of(), OVERWORLD, HERE, "fell_tree", true)
                .isEmpty());
    }

    private static Place place(long id, String name, PlaceKind kind, PlaceSource source, Pos anchor, int radius) {
        return new Place(
                id,
                name,
                kind,
                "",
                OVERWORLD,
                anchor,
                radius,
                Set.of(),
                source,
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                0,
                0,
                0,
                0,
                "",
                Place.defaultBelow(radius),
                Place.defaultAbove(radius));
    }
}
