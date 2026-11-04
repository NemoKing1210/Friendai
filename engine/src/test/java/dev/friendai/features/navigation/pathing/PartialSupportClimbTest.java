package dev.friendai.features.navigation.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.friendai.testutil.GridCellView;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A body standing on a short support is lower than the grid node above that support: a carpet leaves the feet almost a
 * block below the node, a bottom slab half a block. Every climb it plans must still be a rise a real jump clears.
 */
class PartialSupportClimbTest {

    private static List<PathStep> stepsFrom(GridCellView view, Node from, int placeable) {
        List<PathStep> steps = new ArrayList<>();
        new MoveGenerator(view, CostModel.DEFAULTS, new GoalPoint(99, 99, 99, 0, 0))
                .neighbours(from, 0, placeable, 0, steps::add);
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
    void slabCannotStepUpOntoABlockTwoAboveItsFloor() {
        // Feet at 0.5 on the slab, the block's top at 2: a one-node rise is 1.5 real blocks, beyond a jump.
        GridCellView slab = new GridCellView("._.", "..#", "...", "...");
        assertNull(stepTo(stepsFrom(slab, new Node(1, 1, 0), 0), 2, 2, 0));

        // The same block is a normal step from the ground: full-height supports keep their climb.
        GridCellView ground = new GridCellView(".#.", "..#", "...", "...");
        assertEquals(
                Move.ASCEND,
                stepTo(stepsFrom(ground, new Node(1, 1, 0), 0), 2, 2, 0).move());
    }

    @Test
    void nearlyFullSupportStillStepsUp() {
        // A path or farmland is only a sixteenth short of a full block, so the same climb stays well inside the jump.
        GridCellView path = new GridCellView(".f.", "..#", "...", "...");
        assertEquals(
                Move.ASCEND,
                stepTo(stepsFrom(path, new Node(1, 1, 0), 0), 2, 2, 0).move());
    }

    @Test
    void carpetCannotLeapUpOntoABlockTwoAboveItsFloor() {
        // A carpet leaves the feet at 0.06; leaping over the gap onto a block whose top is 2 would be a 1.94 rise.
        GridCellView carpet = new GridCellView(".c..", "...#", "....", "....", "....");
        assertNull(stepTo(stepsFrom(carpet, new Node(1, 1, 0), 0), 3, 2, 0));

        GridCellView path = new GridCellView(".f..", "...#", "....", "....", "....");
        assertEquals(
                Move.JUMP_GAP,
                stepTo(stepsFrom(path, new Node(1, 1, 0), 0), 3, 2, 0).move());
    }

    @Test
    void carpetCannotPillarOntoABlockTwoAboveItsFloor() {
        // Pillaring places a full block in the node's own cell; from a carpet that is a 1.94-block rise as well.
        GridCellView carpet = new GridCellView(".c", "...", "...", "...");
        assertNull(stepTo(stepsFrom(carpet, new Node(1, 1, 0), 1), 1, 2, 0));

        GridCellView ground = new GridCellView(".#", "...", "...", "...");
        PathStep pillar = stepTo(stepsFrom(ground, new Node(1, 1, 0), 1), 1, 2, 0);
        assertNotNull(pillar);
        assertEquals(Move.PILLAR, pillar.move());
    }

    @Test
    void carpetCannotDigUpOntoABlockTwoAboveItsFloor() {
        // Digging out the block over the neighbour and stepping into its place is the same one-node rise a plain step
        // is, so from a carpet it would still be a 1.5-block climb no jump carries.
        GridCellView carpet = new GridCellView("._.", "..#", "..#", "...");
        assertNull(stepTo(stepsFrom(carpet, new Node(1, 1, 0), 0), 2, 2, 0));

        // From the ground the same dig is a normal one-block rise.
        GridCellView ground = new GridCellView(".#.", "..#", "..#", "...");
        PathStep dig = stepTo(stepsFrom(ground, new Node(1, 1, 0), 0), 2, 2, 0);
        assertNotNull(dig);
        assertEquals(Move.DIG_ASCEND, dig.move());
    }
}
