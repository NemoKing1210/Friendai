package dev.friendai.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.testutil.GridCellView;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class GoalHeuristicTest {
    private static final CostModel COSTS = CostModel.DEFAULTS;

    private static List<GridCellView> maps() {
        return List.of(
                new GridCellView("########", "........", "........"),
                new GridCellView("########", "...~....", "........"),
                new GridCellView("########", "...H....", "........"),
                new GridCellView("########", "..#.....", "..#....."),
                new GridCellView("########", ".._.....", "........"),
                new GridCellView("########", "........", "........", "........", "........", "........"));
    }

    @Test
    void pointHeuristicIsZeroInsideTheGoalBand() {
        GoalPoint goal = new GoalPoint(3.5, 1, 0.5, 1.5, 0.5);
        assertEquals(0.0, goal.heuristic(new Node(3, 1, 0), COSTS), 1e-9);
        assertEquals(0.0, goal.heuristic(new Node(2, 1, 0), COSTS), 1e-9);
        assertTrue(goal.heuristic(new Node(0, 1, 0), COSTS) > 0);
    }

    @Test
    void pointHeuristicIsConsistentWithEveryMove() {
        for (GridCellView view : maps()) {
            MoveGenerator moves = new MoveGenerator(view, COSTS, new GoalPoint(99, 99, 99, 0, 0));
            for (int x = 0; x < view.width(); x++) {
                for (int y = 0; y < view.height(); y++) {
                    for (int z = 0; z < view.depth(); z++) {
                        Node from = new Node(x, y, z);
                        List<PathStep> steps = new ArrayList<>();
                        moves.neighbours(from, 0, 64, 0, steps::add);
                        // Same height, a goal above and a goal below, so ascend, pillar and fall are all checked.
                        for (GoalPoint goal : List.of(
                                new GoalPoint(x + 3.5, y, z + 0.5, 0.5, 0.5),
                                new GoalPoint(x + 3.5, y + 3, z + 0.5, 0.5, 0.5),
                                new GoalPoint(x + 3.5, y - 3, z + 0.5, 0.5, 0.5))) {
                            for (PathStep step : steps) {
                                assertTrue(
                                        goal.heuristic(from, COSTS) - goal.heuristic(step.to(), COSTS)
                                                <= step.cost() + 1e-9,
                                        "h(" + from + ") - h(" + step.to() + ") > " + step.cost());
                            }
                            assertTrue(goal.heuristic(from, COSTS) >= 0, "negative heuristic at " + from);
                        }
                    }
                }
            }
        }
    }

    @Test
    void reachHeuristicIsConsistentAndShrinks() {
        GoalReach reach = new GoalReach(List.of(new Pos(6, 1, 0)), 4.0, 1.62);
        double far = reach.heuristic(new Node(1, 1, 0), COSTS);
        double near = reach.heuristic(new Node(4, 1, 0), COSTS);
        assertTrue(near < far);
        assertEquals(0.0, reach.heuristic(new Node(6, 1, 0), COSTS), 1e-9);

        GridCellView view = maps().get(0);
        MoveGenerator moves = new MoveGenerator(view, COSTS, reach);
        for (PathStep step : steps(moves, new Node(2, 1, 0))) {
            assertTrue(
                    reach.heuristic(new Node(2, 1, 0), COSTS) - reach.heuristic(step.to(), COSTS) <= step.cost() + 1e-9,
                    "reach heuristic is not consistent");
        }
    }

    @Test
    void customPointCostsStayConsistentWithEveryMove() {
        assertCustomCostsConsistent(false);
    }

    @Test
    void customReachCostsStayConsistentWithEveryMove() {
        assertCustomCostsConsistent(true);
    }

    private static void assertCustomCostsConsistent(boolean reach) {
        List<CostModel> models = List.of(
                new CostModel(4.6, 6.486, 2, 1, 0.05, 6, 4, 2, 5, 40, 20, 5, 20),
                new CostModel(4.6, 6.486, 0.01, 1, 9, 6, 4, 2, 0.01, 40, 20, 5, 20),
                new CostModel(4.6, 6.486, 2, 0.01, 9, 6, 4, 2, 5, 40, 20, 5, 20),
                new CostModel(0.01, 0.01, 0.01, 100, 100, 100, 0, 0, 0.01, 0, 0, 0, 0));
        for (CostModel costs : models) {
            List<GridCellView> views = new ArrayList<>(maps());
            views.add(new GridCellView("###/###/###", "~~~/~~~/~~~", "~~~/~~~/~~~", ".../.../..."));
            views.add(new GridCellView("###/###/###", ".../.../...", "###/###/##.", ".../.../...", ".../.../..."));
            for (GridCellView view : views) {
                MoveGenerator moves = new MoveGenerator(view, costs, new GoalPoint(99, 99, 99, 0, 0), 0);
                for (int x = 0; x < view.width(); x++) {
                    for (int y = 0; y < view.height(); y++) {
                        for (int z = 0; z < view.depth(); z++) {
                            Node from = new Node(x, y, z);
                            List<PathStep> steps = steps(moves, from);
                            for (int dy : new int[] {-20, 0, 20}) {
                                PathGoal goal = reach
                                        ? new GoalReach(List.of(new Pos(x + 20, y + dy, z + 20)), 1, 1.62)
                                        : new GoalPoint(x + 20.5, y + dy, z + 20.5, 0.5, 0.5);
                                for (PathStep step : steps) {
                                    double reduction = goal.heuristic(from, costs) - goal.heuristic(step.to(), costs);
                                    assertTrue(
                                            reduction <= step.cost() + 1e-9,
                                            () -> goal + " overestimates " + step + " with " + costs);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private static List<PathStep> steps(MoveGenerator moves, Node from) {
        List<PathStep> out = new ArrayList<>();
        moves.neighbours(from, 0, 64, 0, out::add);
        return out;
    }
}
