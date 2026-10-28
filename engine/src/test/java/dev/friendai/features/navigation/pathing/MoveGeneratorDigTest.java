package dev.friendai.features.navigation.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.testutil.GridCellView;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MoveGeneratorDigTest {

    private static final CostModel COSTS = CostModel.DEFAULTS;

    private static List<PathStep> stepsFrom(GridCellView view, Node from) {
        return stepsFrom(view, from, new GoalPoint(99, 99, 99, 0, 0), 16);
    }

    private static List<PathStep> stepsFrom(GridCellView view, Node from, PathGoal goal, int placeable) {
        List<PathStep> steps = new ArrayList<>();
        new MoveGenerator(view, COSTS, goal).neighbours(from, 0, placeable, 0, steps::add);
        return steps;
    }

    private static PathStep stepTo(List<PathStep> steps, int x, int y, int z) {
        for (PathStep step : steps) {
            Node to = step.to();
            if (to.x() == x && to.y() == y && to.z() == z) {
                return step;
            }
        }
        return null;
    }

    @Test
    void digsThroughStone() {
        GridCellView view = new GridCellView("#####", "..##.", "..##.", ".....");
        PathStep step = stepTo(stepsFrom(view, new Node(1, 1, 0)), 2, 1, 0);
        assertEquals(Move.DIG, step.move());
        assertEquals(2 * (30 + COSTS.breakExtra()) + COSTS.walk(), step.cost(), 1e-9);
        assertEquals(List.of(new Pos(2, 2, 0), new Pos(2, 1, 0)), step.breaks());
    }

    @Test
    void neverDigsOwnSupport() {
        GridCellView view = new GridCellView("###", "###", "###", ".##", "...");
        Node from = new Node(1, 1, 0);
        for (PathStep step : stepsFrom(view, from)) {
            assertFalse(step.breaks().contains(from.support()), step.toString());
        }
    }

    @Test
    void refusesBreakNextToLava() {
        GridCellView view = new GridCellView("#####/#####", "..##./..L..", "..##./.....", "...../.....");
        assertNull(stepTo(stepsFrom(view, new Node(1, 1, 0)), 2, 1, 0));
    }

    @Test
    void penalisesBreakNextToWater() {
        GridCellView dry = new GridCellView("#####/#####", "..##./..##.", "..##./.....", "...../.....");
        GridCellView wet = new GridCellView("#####/#####", "..##./..~..", "..##./.....", "...../.....");
        double dryCost = stepTo(stepsFrom(dry, new Node(1, 1, 0)), 2, 1, 0).cost();
        double wetCost = stepTo(stepsFrom(wet, new Node(1, 1, 0)), 2, 1, 0).cost();
        assertEquals(dryCost + COSTS.waterNearBreak(), wetCost, 1e-9);
    }

    @Test
    void countsGravelColumn() {
        GridCellView stoneOnly = new GridCellView("#####", "..##.", "..##.", ".....");
        GridCellView gravel = new GridCellView("#####", "..##.", "..g..", ".....");
        double plain = stepTo(stepsFrom(stoneOnly, new Node(1, 1, 0)), 2, 1, 0).cost();
        PathStep step = stepTo(stepsFrom(gravel, new Node(1, 1, 0)), 2, 1, 0);
        assertTrue(step.breaks().contains(new Pos(2, 2, 0)));
        assertEquals(
                plain - (30 + COSTS.breakExtra()) + (9 + COSTS.breakExtra() + COSTS.fallingExtra()), step.cost(), 1e-9);
    }

    @Test
    void pillarsWhenBlocksAvailable() {
        GridCellView view = new GridCellView("#", ".", ".", ".");
        PathStep step = stepTo(stepsFrom(view, new Node(0, 1, 0), new GoalPoint(0.5, 3, 0.5, 0.5, 0.5), 3), 0, 2, 0);
        assertEquals(Move.PILLAR, step.move());
        assertEquals(List.of(new Pos(0, 1, 0)), step.places());
    }

    @Test
    void noPillarWithoutBlocks() {
        GridCellView view = new GridCellView("#", ".", ".", ".");
        assertTrue(stepsFrom(view, new Node(0, 1, 0), new GoalPoint(0.5, 3, 0.5, 0.5, 0.5), 0).stream()
                .noneMatch(step -> step.move() == Move.PILLAR));
    }

    @Test
    void bridgesOverHole() {
        GridCellView view = new GridCellView("#.#", "...", "...");
        PathStep step = stepTo(stepsFrom(view, new Node(0, 1, 0), new GoalPoint(2.5, 1, 0.5, 0.5, 0.5), 3), 1, 1, 0);
        assertEquals(Move.BRIDGE, step.move());
        assertEquals(List.of(new Pos(1, 0, 0)), step.places());
    }

    @Test
    void neverBreaksProtectedTarget() {
        GridCellView view = new GridCellView("#####", "..##.", "..##.", ".....");
        GoalReach goal = new GoalReach(List.of(new Pos(2, 1, 0)), 4.0, 1.62);
        assertNull(stepTo(stepsFrom(view, new Node(1, 1, 0), goal, 0), 2, 1, 0));
    }

    @Test
    void blacklistedCellIsNotBroken() {
        GridCellView view = new GridCellView("#####", "..pp.", "..pp.", ".....");
        assertNull(stepTo(stepsFrom(view, new Node(1, 1, 0)), 2, 1, 0));
    }
}
