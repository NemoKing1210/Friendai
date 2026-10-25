package dev.friendai.movement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FacingTest {
    private static final float EPS = 0.001f;

    @Test
    void normalizeWrapsIntoHalfOpenRange() {
        assertEquals(-180.0f, Facing.normalize(180.0f), EPS);
        assertEquals(-170.0f, Facing.normalize(190.0f), EPS);
        assertEquals(170.0f, Facing.normalize(-190.0f), EPS);
        assertEquals(0.0f, Facing.normalize(720.0f), EPS);
    }

    @Test
    void yawTowardUsesGameConvention() {
        assertEquals(0.0f, Facing.yawToward(0, 5), EPS);
        assertEquals(90.0f, Facing.yawToward(-5, 0), EPS);
        assertEquals(-90.0f, Facing.yawToward(5, 0), EPS);
        assertEquals(-180.0f, Facing.yawToward(0, -5), EPS);
        assertEquals(0.0f, Facing.yawToward(0, 0), EPS);
    }

    @Test
    void pitchTowardLooksUpAndDown() {
        assertEquals(-90.0f, Facing.pitchToward(0, 4, 0), EPS);
        assertEquals(90.0f, Facing.pitchToward(0, -4, 0), EPS);
        assertEquals(-45.0f, Facing.pitchToward(3, 3, 0), EPS);
    }

    @Test
    void stepTowardTakesTheShortWayAndNeverOvershoots() {
        assertEquals(170.0f, Facing.stepToward(-170.0f, 170.0f, 20.0f), EPS);
        assertEquals(-175.0f, Facing.stepToward(175.0f, -170.0f, 10.0f), EPS);
        assertEquals(30.0f, Facing.stepToward(0.0f, 30.0f, 90.0f), EPS);
        assertEquals(10.0f, Facing.stepToward(0.0f, 90.0f, 10.0f), EPS);
    }

    @Test
    void differenceIsSymmetricAcrossTheSeam() {
        assertEquals(20.0f, Facing.difference(170.0f, -170.0f), EPS);
        assertEquals(20.0f, Facing.difference(-170.0f, 170.0f), EPS);
    }

    @Test
    void relativeDirectionsFollowTheFacing() {
        // Facing south (yaw 0): forward is +Z, left is east (+X), right is west (-X).
        var forward = Facing.relative(0.0f, "forward", 2).orElseThrow();
        assertEquals(0.0, forward.dx(), 1e-9);
        assertEquals(2.0, forward.dz(), 1e-9);
        var left = Facing.relative(0.0f, "left", 1).orElseThrow();
        assertEquals(1.0, left.dx(), 1e-9);
        assertEquals(0.0, left.dz(), 1e-9);
        var right = Facing.relative(0.0f, "right", 1).orElseThrow();
        assertEquals(-1.0, right.dx(), 1e-9);
        var back = Facing.relative(0.0f, "back", 3).orElseThrow();
        assertEquals(-3.0, back.dz(), 1e-9);
        // Facing west (yaw 90): forward is -X.
        assertEquals(-1.0, Facing.relative(90.0f, "forward", 1).orElseThrow().dx(), 1e-9);
        assertTrue(Facing.relative(0.0f, "sideways", 1).isEmpty());
    }

    @Test
    void compassRoundTrips() {
        for (String name : new String[] {"north", "south", "east", "west"}) {
            assertEquals(name, Facing.compassName(Facing.compass(name).orElseThrow()));
        }
        assertTrue(Facing.compass("up").isEmpty());
    }
}
