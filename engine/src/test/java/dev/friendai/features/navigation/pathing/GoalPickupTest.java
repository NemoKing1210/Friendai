package dev.friendai.features.navigation.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class GoalPickupTest {

    /** A coal drop resting at the bottom of the one-block pit the companion just dug. */
    private static final DropPoint IN_A_PIT = new DropPoint(6.5, 1.05, 2.5);

    @Test
    void aDropInTheHoleItDugIsReachedFromTheRim() {
        GoalPickup goal = new GoalPickup(List.of(IN_A_PIT), 1.5);
        assertTrue(goal.isGoal(new Node(6, 2, 2), null), "standing over the pit");
        assertTrue(goal.isGoal(new Node(7, 2, 2), null), "standing at the rim");
    }

    @Test
    void theEyesReachFartherThanTheBodyDoes() {
        // Two cells of horizontal gap leave the item outside the inflated body box, although the eyes are not far.
        GoalPickup goal = new GoalPickup(List.of(IN_A_PIT), 1.5);
        assertFalse(goal.isGoal(new Node(8, 2, 2), null));
        assertFalse(goal.reaches(8.5, 2, 2.5, 1.5));
    }

    @Test
    void aDropUnderTheFeetIsReached() {
        GoalPickup goal = new GoalPickup(List.of(new DropPoint(4.5, 2.05, 2.5)), 1.5);
        assertTrue(goal.isGoal(new Node(4, 2, 2), null));
    }

    @Test
    void aDropTooDeepToTouchIsNotReachedFromTheRim() {
        DropPoint deep = new DropPoint(6.5, -1.05, 2.5);
        GoalPickup goal = new GoalPickup(List.of(deep), 1.5);
        assertFalse(goal.isGoal(new Node(6, 2, 2), null));
        assertTrue(goal.isGoal(new Node(6, 0, 2), null), "standing on the floor of the same pit");
    }

    @Test
    void theHeuristicIsZeroWhenReachedAndNeverNegative() {
        GoalPickup goal = new GoalPickup(List.of(IN_A_PIT), 1.5);
        assertEquals(0.0, goal.heuristic(new Node(6, 2, 2), CostModel.DEFAULTS));
        assertTrue(goal.heuristic(new Node(12, 2, 2), CostModel.DEFAULTS) > 0);
        assertTrue(goal.heuristic(new Node(-20, 2, 2), CostModel.DEFAULTS) >= 0);
    }

    @Test
    void anyOfSeveralDropsIsEnough() {
        GoalPickup goal = new GoalPickup(List.of(new DropPoint(20.5, 2.05, 2.5), IN_A_PIT), 1.5);
        assertTrue(goal.isGoal(new Node(7, 2, 2), null));
        assertTrue(goal.reaches(7.5, 2, 2.5, 1.5));
        assertFalse(goal.reaches(12.5, 2, 2.5, 1.5));
    }
}
