package dev.friendai.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.testutil.GridCellView;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class GoalTest {

    @Test
    void pointProtectsItsCellAndSupport() {
        GoalPoint goal = new GoalPoint(3.5, 1.0, 0.5, 0.5, 0.5);
        assertTrue(goal.protects(new Pos(3, 1, 0)));
        assertTrue(goal.protects(new Pos(3, 0, 0)));
        assertFalse(goal.protects(new Pos(3, 2, 0)));
        assertFalse(goal.protects(new Pos(4, 1, 0)));
    }

    @Test
    void pointWithinTolerance() {
        GoalPoint goal = new GoalPoint(3.5, 1.0, 0.5, 1.5, 3.0);
        assertTrue(goal.isGoal(new Node(2, 1, 0), null));
        assertTrue(goal.isGoal(new Node(2, 3, 0), null));
        assertFalse(goal.isGoal(new Node(0, 1, 0), null));
    }

    @Test
    void pointHeuristicZeroInside() {
        GoalPoint goal = new GoalPoint(3.5, 1.0, 0.5, 1.5, 3.0);
        assertEquals(0.0, goal.heuristic(new Node(2, 1, 0), CostModel.DEFAULTS));
        assertEquals(0.0, goal.heuristic(new Node(3, 1, 0), CostModel.DEFAULTS));
        assertTrue(goal.heuristic(new Node(0, 1, 0), CostModel.DEFAULTS) > 0);
    }

    @Test
    void snapDownLandsOnGround() {
        // A floor at y = 8 and a target 12 blocks above it: inside the 16-block drop budget.
        GridCellView v = tallGrid(21, 8);
        assertEquals(new Pos(2, 9, 0), GoalPoint.snapDown(v, 2, 20, 0, 16));
    }

    /** Air all the way up, with a stone floor at {@code floorY}. */
    private static GridCellView tallGrid(int height, int floorY) {
        String[] layers = new String[height];
        for (int y = 0; y < height; y++) {
            layers[y] = y == floorY ? "########" : "........";
        }
        return new GridCellView(layers);
    }

    @Test
    void snapDownKeepsGroundTarget() {
        GridCellView v = new GridCellView("#####", ".....", ".....");
        assertEquals(new Pos(2, 1, 0), GoalPoint.snapDown(v, 2, 1, 0, 16));
    }

    @Test
    void snapDownTreatsWaterAsLanding() {
        GridCellView v = new GridCellView("#####", "..~..", ".....");
        assertEquals(new Pos(2, 1, 0), GoalPoint.snapDown(v, 2, 10, 0, 16));
    }

    @Test
    void snapDownGivesUpAfterMaxDrop() {
        GridCellView v = new GridCellView("#####", ".....", ".....");
        assertEquals(new Pos(2, 20, 0), GoalPoint.snapDown(v, 2, 20, 0, 3));
    }

    @Test
    void reachNeedsDistance() {
        GridCellView v = new GridCellView("######", "......", "......", "......");
        GoalReach reach = new GoalReach(List.of(new Pos(5, 2, 0)), 4.0, 1.62);
        assertFalse(reach.isGoal(new Node(0, 1, 0), v));
        assertTrue(reach.isGoal(new Node(2, 1, 0), v));
    }

    @Test
    void reachNeedsSight() {
        GridCellView v = new GridCellView("#####", ".....", "...G.", ".....");
        GoalReach reach = new GoalReach(List.of(new Pos(4, 2, 0)), 4.0, 1.62);
        assertFalse(reach.isGoal(new Node(1, 1, 0), v));
        assertNull(reach.reachedTarget(new Node(1, 1, 0), v));
    }

    @Test
    void reachProtectsTargets() {
        GoalReach reach = new GoalReach(List.of(new Pos(4, 2, 0)), 4.0, 1.62);
        assertTrue(reach.protects(new Pos(4, 2, 0)));
        assertFalse(reach.protects(new Pos(4, 3, 0)));
    }

    @Test
    void reachPicksAnyTarget() {
        GridCellView v = new GridCellView("######", "......", "......", "......");
        GoalReach reach = new GoalReach(List.of(new Pos(9, 2, 0), new Pos(2, 2, 0)), 4.0, 1.62);
        assertEquals(new Pos(2, 2, 0), reach.reachedTarget(new Node(0, 1, 0), v));
        assertSame(reach.targets().get(1), reach.reachedTarget(new Node(0, 1, 0), v));
    }

    @Test
    void reachDoesNotCountStandingInTheTarget() {
        GridCellView v = new GridCellView("######", "......", "......", "......");
        GoalReach reach = new GoalReach(List.of(new Pos(3, 2, 0)), 4.0, 1.62);
        // Feet in the target cell, or head in it: either way the block cannot be placed from there.
        assertNull(reach.reachedTarget(new Node(3, 2, 0), v));
        assertNull(reach.reachedTarget(new Node(3, 1, 0), v));
        assertNotNull(reach.reachedTarget(new Node(3, 4, 0), v));
    }

    @Test
    void reachHeuristicShrinksNearTarget() {
        GridCellView v = new GridCellView("######", "......", "......", "......");
        GoalReach reach = new GoalReach(List.of(new Pos(5, 2, 0)), 4.0, 1.62);
        double far = reach.heuristic(new Node(1, 1, 0), CostModel.DEFAULTS);
        double near = reach.heuristic(new Node(3, 1, 0), CostModel.DEFAULTS);
        assertTrue(near < far);
        assertTrue(far > 0);
    }

    @Test
    void groundBelowFindsTheSurface() {
        assertEquals(Optional.of(new Pos(2, 9, 0)), GoalPoint.groundBelow(tallGrid(21, 8), 2, 20, 0, 16));
    }

    @Test
    void groundBelowOverTheVoidIsEmpty() {
        GridCellView view = new GridCellView("........", "........", "........");
        assertTrue(GoalPoint.groundBelow(view, 2, 10, 0, 16).isEmpty());
    }

    @Test
    void groundBelowSkipsASolidFootCell() {
        GridCellView view = new GridCellView("#####", "..#..", ".....", ".....");
        assertTrue(GoalPoint.groundBelow(view, 2, 1, 0, 1).isEmpty());
        assertEquals(Optional.of(new Pos(2, 2, 0)), GoalPoint.groundBelow(view, 2, 3, 0, 4));
    }

    @Test
    void groundBelowNeedsHeadClearance() {
        GridCellView view = new GridCellView("#####", ".....", "..#..", ".....");
        assertTrue(GoalPoint.groundBelow(view, 2, 1, 0, 1).isEmpty());
    }
}
