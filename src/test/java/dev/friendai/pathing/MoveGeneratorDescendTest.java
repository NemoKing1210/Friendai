package dev.friendai.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.friendai.testutil.GridCellView;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MoveGeneratorDescendTest {

    private static final CostModel COSTS = CostModel.DEFAULTS;

    private static List<PathStep> stepsFrom(GridCellView view, Node from) {
        List<PathStep> steps = new ArrayList<>();
        new MoveGenerator(view, COSTS, new GoalPoint(99, 99, 99, 0, 0)).neighbours(from, 0, 0, 0, steps::add);
        return steps;
    }

    private static PathStep stepTo(List<PathStep> steps, int x, int y, int z) {
        for (PathStep step : steps) {
            Node to = step.to();
            if (to.x() == x && to.y() == y && to.z() == z && step.move() == Move.DESCEND) {
                return step;
            }
        }
        return null;
    }

    @Test
    void dropsTwoBlocksOntoGround() {
        GridCellView view = new GridCellView("###", "#..", "#..", "...", "...");
        PathStep step = stepTo(stepsFrom(view, new Node(0, 3, 0)), 1, 1, 0);
        assertNotNull(step);
        assertEquals(COSTS.walk() + COSTS.fall() * 2, step.cost(), 1e-9);
    }

    @Test
    void refusesADropOfFiveOntoStone() {
        GridCellView view = new GridCellView("###", "#..", "#..", "#..", "#..", "#..", "...", "...");
        assertNull(stepTo(stepsFrom(view, new Node(0, 6, 0)), 1, 1, 0));
    }

    @Test
    void aCeilingOverThePitBlocksTheFall() {
        GridCellView view = new GridCellView("###", "#..", "#..", "...", ".#.");
        assertNull(stepTo(stepsFrom(view, new Node(0, 3, 0)), 1, 1, 0));
    }

    @Test
    void entersDeepWaterAtItsSurfaceFromAnyHeight() {
        GridCellView view = new GridCellView("###", "#~~", "#~~", "#~~", "#~~", "#~~", "...", "...");
        PathStep entry = stepTo(stepsFrom(view, new Node(0, 6, 0)), 1, 5, 0);
        assertNotNull(entry);
        assertEquals(Move.DESCEND, entry.move());
        assertEquals(COSTS.walk() + COSTS.fall(), entry.cost(), 1e-9);
        assertNull(
                stepTo(stepsFrom(view, new Node(0, 6, 0)), 1, 1, 0),
                "landed under the water instead of on its surface");
    }

    @Test
    void dropsDiagonallyWhenBothCornersAreOpen() {
        GridCellView view = new GridCellView("###/###/###", "#../.../...", "#../.../...", ".../.../...", ".../.../...");
        List<PathStep> steps = stepsFrom(view, new Node(0, 3, 0));
        assertNotNull(stepTo(steps, 1, 1, 1));
    }

    @Test
    void aBlockedCornerStopsTheDiagonalDrop() {
        GridCellView view = new GridCellView("###/###/###", "#../.../...", "#../.../...", ".#./.../...", ".../.../...");
        List<PathStep> steps = stepsFrom(view, new Node(0, 3, 0));
        assertNull(stepTo(steps, 1, 1, 1));
    }
}
