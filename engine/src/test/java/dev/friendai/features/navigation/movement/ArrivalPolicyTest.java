package dev.friendai.features.navigation.movement;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.features.navigation.pathing.Move;
import org.junit.jupiter.api.Test;

class ArrivalPolicyTest {

    @Test
    void insideTheRadiusArrivesOnlyWhenLevel() {
        assertTrue(ArrivalPolicy.arrived(Move.ASCEND, 1, 0, 0.1, 0.0, 0.5, true));
        assertFalse(ArrivalPolicy.arrived(Move.ASCEND, 1, 0, 0.1, 0.0, 0.5, false));
    }

    @Test
    void walkPastTheCentreStillInTheCellHasArrived() {
        assertTrue(ArrivalPolicy.arrived(Move.WALK, 1, 0, 0.4, 0.1, 0.3, true));
    }

    @Test
    void ascendPastTheCentreStillInTheCellHasArrived() {
        assertTrue(ArrivalPolicy.arrived(Move.ASCEND, 0, -1, 0.1, -0.45, 0.3, true));
    }

    @Test
    void diagonalWalkAndAscendPastTheCentreHaveArrived() {
        assertTrue(ArrivalPolicy.arrived(Move.WALK_DIAGONAL, 1, 1, 0.4, 0.4, 0.3, true));
        assertTrue(ArrivalPolicy.arrived(Move.ASCEND, -1, 1, -0.4, 0.45, 0.3, true));
        assertTrue(ArrivalPolicy.arrived(Move.DESCEND, 1, -1, 0.45, -0.2, 0.3, true));
    }

    @Test
    void shortOfTheCentreHasNotArrived() {
        assertFalse(ArrivalPolicy.arrived(Move.WALK, 1, 0, -0.4, 0.0, 0.3, true));
        assertFalse(ArrivalPolicy.arrived(Move.WALK_DIAGONAL, 1, 1, -0.4, -0.4, 0.3, true));
    }

    @Test
    void outsideTheCellHasNotArrived() {
        assertFalse(ArrivalPolicy.arrived(Move.WALK, 1, 0, 0.6, 0.0, 0.3, true));
        assertFalse(ArrivalPolicy.arrived(Move.ASCEND, 1, 0, 0.2, 0.7, 0.3, true));
        assertFalse(ArrivalPolicy.arrived(Move.WALK_DIAGONAL, 1, 1, 0.6, 0.2, 0.3, true));
    }

    @Test
    void notLevelNeverArrivesByMomentum() {
        assertFalse(ArrivalPolicy.arrived(Move.ASCEND, 1, 0, 0.4, 0.0, 0.3, false));
    }

    @Test
    void movesWithoutMomentumNeedTheRadius() {
        assertFalse(ArrivalPolicy.arrived(Move.CLIMB, 1, 0, 0.4, 0.0, 0.3, true));
        assertFalse(ArrivalPolicy.arrived(Move.JUMP_GAP, 2, 0, 0.4, 0.0, 0.3, true));
        assertFalse(ArrivalPolicy.arrived(Move.DOOR, 1, 0, 0.4, 0.0, 0.3, true));
    }

    @Test
    void aStepWithoutHorizontalChangeHasNoDirection() {
        assertFalse(ArrivalPolicy.arrived(Move.DESCEND, 0, 0, 0.4, 0.0, 0.3, true));
    }
}
