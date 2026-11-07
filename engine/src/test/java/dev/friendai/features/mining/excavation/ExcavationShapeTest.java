package dev.friendai.features.mining.excavation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.foundation.spatial.Pos;
import org.junit.jupiter.api.Test;

class ExcavationShapeTest {
    @Test
    void boxIsTopDownAndBounded() {
        ExcavationGeometry box = ExcavationGeometry.box(0, 0, 0, 2, 1, 1).orElseThrow();

        assertEquals(12, box.volume());
        assertEquals(Pos.of(0, 1, 0), box.cells().get(0));
        assertTrue(box.contains(Pos.of(2, 0, 1)));
        assertTrue(ExcavationGeometry.box(0, 0, 0, 100, 100, 100).isEmpty());
    }

    @Test
    void tunnelRunsAlongItsDirection() {
        ExcavationGeometry tunnel = ExcavationGeometry.tunnel(ExcavationDirection.EAST, 0, 0, 0, 3, 1, 2)
                .orElseThrow();

        assertEquals(6, tunnel.volume());
        assertEquals(Pos.of(0, 0, 0), tunnel.cells().get(0));
        assertEquals(Pos.of(0, 1, 0), tunnel.cells().get(1));
        assertEquals(Pos.of(1, 0, 0), tunnel.cells().get(2));
        assertFalse(tunnel.contains(Pos.of(3, 0, 0)));
    }

    @Test
    void stairDropsOneBlockPerStep() {
        ExcavationGeometry stair = ExcavationGeometry.stair(ExcavationDirection.EAST, 0, 2, 0, 3, 1, 2)
                .orElseThrow();

        assertEquals(9, stair.volume());
        assertTrue(stair.contains(Pos.of(0, 4, 0)), "the headroom cell of the first step is part of the passage");
        assertTrue(stair.contains(Pos.of(2, 1, 0)));
        assertFalse(stair.contains(Pos.of(2, 3, 0)));
    }

    @Test
    void stairIsAlwaysHighEnoughToWalkDown() {
        ExcavationGeometry stair = ExcavationGeometry.stair(ExcavationDirection.EAST, 0, 2, 0, 3, 1, 1)
                .orElseThrow();

        assertEquals(ExcavationGeometry.MIN_STAIR_HEIGHT, stair.height(), "a stair is a passage, not a trench");
        assertEquals(9, stair.volume());
        assertTrue(stair.contains(Pos.of(0, 3, 0)), "the head cell of the first step is part of the passage");
    }

    @Test
    void tallStairKeepsTheRequestedHeight() {
        assertEquals(
                5,
                ExcavationGeometry.stair(ExcavationDirection.EAST, 0, 2, 0, 2, 1, 5)
                        .orElseThrow()
                        .height());
    }

    @Test
    void shaftRunsDownOrUp() {
        ExcavationGeometry shaft = ExcavationGeometry.shaft(ExcavationDirection.DOWN, 0, 2, 0, 3, 1, 1)
                .orElseThrow();

        assertEquals(3, shaft.volume());
        assertTrue(shaft.contains(Pos.of(0, 2, 0)));
        assertTrue(shaft.contains(Pos.of(0, 0, 0)));
        assertTrue(ExcavationGeometry.shaft(ExcavationDirection.EAST, 0, 2, 0, 3, 1, 1)
                .isEmpty());
    }

    @Test
    void stripAlternatesItsBranches() {
        ExcavationGeometry strip = ExcavationGeometry.strip(ExcavationDirection.EAST, 0, 0, 0, 6, 1, 1, 2, 2, 2)
                .orElseThrow();

        assertEquals(10, strip.volume());
        assertTrue(strip.contains(Pos.of(2, 0, -1)));
        assertTrue(strip.contains(Pos.of(2, 0, -2)));
        assertTrue(strip.contains(Pos.of(4, 0, 1)));
        assertTrue(strip.contains(Pos.of(4, 0, 2)));
    }

    @Test
    void corridorRejectsVerticalOrOversizedRuns() {
        assertTrue(ExcavationGeometry.tunnel(ExcavationDirection.DOWN, 0, 0, 0, 3, 1, 1)
                .isEmpty());
        assertTrue(ExcavationGeometry.tunnel(ExcavationDirection.EAST, 0, 0, 0, 129, 1, 1)
                .isEmpty());
        assertTrue(ExcavationGeometry.strip(ExcavationDirection.EAST, 0, 0, 0, 3, 1, 1, 17, 2, 2)
                .isEmpty());
    }
}
