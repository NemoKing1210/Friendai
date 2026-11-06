package dev.friendai.world.movement;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** The last walk of an entity approach has to leave room for the height it is aiming at. */
class EntityApproachTest {

    @Test
    void aTargetOnTheFlatIsApproachedToTheFullReach() {
        assertEquals(2.0, EntityApproach.horizontalReach(2.0, 0.0), 1e-9);
    }

    @Test
    void aTargetHalfABlockUpLeavesLessRoomOnTheGround() {
        assertEquals(Math.sqrt(0.75), EntityApproach.horizontalReach(1.0, 0.5), 1e-9);
        assertEquals(Math.sqrt(3.0), EntityApproach.horizontalReach(2.0, 1.0), 1e-9);
    }

    @Test
    void aTargetHigherThanTheReachStillAsksForTheClosestStep() {
        // The three-dimensional distance cannot fit anyway, so the walk stops at the smallest useful gap instead of a
        // negative or zero allowance that would never arrive.
        assertEquals(0.5, EntityApproach.horizontalReach(1.0, 4.0), 1e-9);
        assertEquals(0.5, EntityApproach.horizontalReach(1.0, -4.0), 1e-9);
    }
}
