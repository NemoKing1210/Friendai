package dev.friendai.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.testutil.GridCellView;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DiagonalMoveTest {
    private static final CostModel COSTS = CostModel.DEFAULTS;

    private static List<PathStep> stepsFrom(GridCellView view, Node from) {
        List<PathStep> out = new ArrayList<>();
        new MoveGenerator(view, COSTS, new GoalPoint(99, 99, 99, 0, 0)).neighbours(from, 0, 64, 0, out::add);
        return out;
    }

    private static PathStep stepTo(List<PathStep> steps, int x, int y, int z) {
        return steps.stream().filter(s -> s.to().equals(new Node(x, y, z))).findFirst().orElse(null);
    }

    @Test
    void diagonalAscendNeedsBothCorners() {
        GridCellView view = new GridCellView("###/###/###", ".../.../..#", ".../.../...", ".../.../...");
        PathStep step = stepTo(stepsFrom(view, new Node(1, 1, 1)), 2, 2, 2);
        assertTrue(step != null && step.move() == Move.ASCEND, "no diagonal ascend: " + step);
        assertEquals(COSTS.walk() + COSTS.jump(), step.cost(), 1e-9);

        GridCellView walled = new GridCellView("###/###/###", ".../..#/..#", ".../.../...", ".../.../...");
        assertNull(stepTo(stepsFrom(walled, new Node(1, 1, 1)), 2, 2, 2),
                "a diagonal ascent through a solid corner must not be emitted");
    }

    @Test
    void diagonalDescendIsOneMove() {
        GridCellView view = new GridCellView(
                "###/###/###",
                ".../.../...",
                "###/###/##.",
                ".../.../...",
                ".../.../...");
        PathStep step = stepTo(stepsFrom(view, new Node(1, 3, 1)), 2, 1, 2);
        assertTrue(step != null && step.move() == Move.DESCEND, "no diagonal descend: " + step);
        assertEquals(COSTS.walk() + 2 * COSTS.fall(), step.cost(), 1e-9);
    }

    @Test
    void diagonalSwimUsesTheSwimPrice() {
        GridCellView view = new GridCellView("####/####", "~~~~/~~~~", "~~~~/~~~~");
        PathStep step = stepTo(stepsFrom(view, new Node(1, 1, 1)), 2, 1, 0);
        assertTrue(step != null && step.move() == Move.SWIM, "no diagonal swim: " + step);
        assertEquals(COSTS.swim() * Math.sqrt(2.0) * MoveGenerator.SUBMERGED_FACTOR, step.cost(), 1e-9);
    }
}
