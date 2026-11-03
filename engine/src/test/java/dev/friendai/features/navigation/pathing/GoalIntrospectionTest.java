package dev.friendai.features.navigation.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Each goal kind describes itself and the cells it asks for; the overlay reads these instead of switching on types. */
class GoalIntrospectionTest {

    @Test
    void pointDescribesOneCellAtItsFloor() {
        GoalPoint goal = new GoalPoint(3.7, 64.0, -2.2, 1.5, 3.0);

        assertEquals("point 3 64 -3", goal.label());
        assertEquals(List.of(new Pos(3, 64, -3)), goal.cells());
    }

    @Test
    void reachDescribesItsTargetCells() {
        GoalReach one = new GoalReach(List.of(new Pos(1, 2, 3)), 2.5, 1.62);
        assertEquals("one cell", one.label());
        assertEquals(List.of(new Pos(1, 2, 3)), one.cells());

        List<Pos> many = new ArrayList<>();
        for (int i = 0; i < 70; i++) {
            many.add(new Pos(i, 2, 3));
        }
        GoalReach capped = new GoalReach(many, 2.5, 1.62);
        assertEquals("70 cells", capped.label());
        assertEquals(64, capped.cells().size());
    }

    @Test
    void reachKeepsItsTargetsWhenTheCallerMutatesTheList() {
        List<Pos> targets = new ArrayList<>(List.of(new Pos(1, 2, 3)));
        GoalReach goal = new GoalReach(targets, 2.5, 1.62);

        targets.add(new Pos(4, 5, 6));

        assertEquals(1, goal.targets().size());
        assertEquals(1, goal.cells().size());
    }

    @Test
    void pickupDescribesItsDropCells() {
        GoalPickup one = new GoalPickup(List.of(new DropPoint(1.5, 64.0, 2.5)), 1.0);
        assertEquals("pickup", one.label());
        assertEquals(List.of(new Pos(1, 64, 2)), one.cells());

        List<DropPoint> drops = new ArrayList<>();
        for (int i = 0; i < 70; i++) {
            drops.add(new DropPoint(i, 64.0, 2.0));
        }
        GoalPickup many = new GoalPickup(drops, 1.0);
        assertEquals("70 drops", many.label());
        assertEquals(64, many.cells().size());
    }

    @Test
    void anUnknownGoalKindFallsBackToTheGenericDescription() {
        PathGoal goal = new PathGoal() {
            @Override
            public boolean isGoal(Node node, CellView view) {
                return false;
            }

            @Override
            public double heuristic(Node node, CostModel costs) {
                return 0.0;
            }
        };

        assertEquals("goal", goal.label());
        assertEquals(List.of(), goal.cells());
    }
}
