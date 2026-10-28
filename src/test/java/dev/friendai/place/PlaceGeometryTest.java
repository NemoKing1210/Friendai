package dev.friendai.place;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.pathing.Pos;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PlaceGeometryTest {
    private static Place home(int radius, int below, int above) {
        return new Place(
                1,
                "home",
                PlaceKind.HOUSE,
                "",
                "minecraft:overworld",
                new Pos(0, 64, 0),
                radius,
                Set.of(),
                PlaceSource.MANUAL,
                null,
                1,
                1,
                0,
                0,
                "",
                below,
                above);
    }

    @Test
    void aRadiusThirtyTwoHomeDoesNotOwnTheStoneFourBelowItsFloor() {
        Place home = home(32, 1, 8);
        assertFalse(home.contains(new Pos(0, 60, 0)), "four below the anchor is outside the band");
        assertTrue(home.contains(new Pos(0, 63, 0)), "one below the anchor is its floor");
    }

    @Test
    void theBandBoundsTheHeightExactly() {
        Place home = home(8, 2, 5);
        assertTrue(home.contains(new Pos(0, 66, 0)));
        assertTrue(home.contains(new Pos(0, 69, 0)));
        assertFalse(home.contains(new Pos(0, 70, 0)), "one above the band");
        assertTrue(home.contains(new Pos(0, 62, 0)));
        assertFalse(home.contains(new Pos(0, 61, 0)), "one below the band");
    }

    @Test
    void theHorizontalSquareStaysASquare() {
        Place home = home(8, 1, 8);
        assertTrue(home.contains(new Pos(8, 64, -8)));
        assertFalse(home.contains(new Pos(9, 64, 0)));
        assertFalse(home.contains(new Pos(0, 64, -9)));
    }

    @Test
    void theDefaultBandIsTheFloorAndEightAbove() {
        Place home = home(32, Place.defaultBelow(32), Place.defaultAbove(32));
        assertTrue(home.contains(new Pos(0, 72, 0)));
        assertFalse(home.contains(new Pos(0, 73, 0)));
        assertFalse(home.contains(new Pos(0, 60, 0)));
        assertTrue(home.contains(new Pos(0, 63, 0)));
    }

    @Test
    void anExplicitBandMayReachUnderground() {
        Place shaft = home(4, 16, 2);
        assertTrue(shaft.contains(new Pos(0, 50, 0)));
        assertFalse(shaft.contains(new Pos(0, 47, 0)));
    }
}
