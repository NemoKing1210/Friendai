package dev.friendai.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.testutil.GridCellView;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MoveGeneratorWalkTest {

    private static final CostModel COSTS = CostModel.DEFAULTS;

    private static List<PathStep> stepsFrom(GridCellView view, Node from) {
        List<PathStep> steps = new ArrayList<>();
        new MoveGenerator(view, COSTS, new GoalPoint(99, 99, 99, 0, 0)).neighbours(from, 0, 0, 0, steps::add);
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

    private static GridCellView room() {
        return new GridCellView("###/###/###", "..../..../....", "..../..../....");
    }

    @Test
    void walksFourWays() {
        List<PathStep> steps = stepsFrom(room(), new Node(1, 1, 1));
        int walks = 0;
        for (PathStep step : steps) {
            if (step.move() == Move.WALK) {
                walks++;
                assertEquals(COSTS.walk(), step.cost(), 1e-9);
            }
        }
        assertEquals(4, walks);
    }

    @Test
    void diagonalNeedsBothCorners() {
        List<PathStep> open = stepsFrom(room(), new Node(1, 1, 1));
        assertTrue(stepTo(open, 2, 1, 2).move() == Move.WALK_DIAGONAL);

        GridCellView walled = new GridCellView("###/###/###", "..../..#./....", "..../..../....");
        List<PathStep> blocked = stepsFrom(walled, new Node(1, 1, 1));
        assertTrue(stepTo(blocked, 2, 1, 2) == null);
    }

    @Test
    void stepsUpOneBlock() {
        GridCellView view = new GridCellView("#####", "..#..", ".....", ".....");
        PathStep step = stepTo(stepsFrom(view, new Node(1, 1, 0)), 2, 2, 0);
        assertEquals(Move.ASCEND, step.move());
        assertEquals(COSTS.walk() + COSTS.jump(), step.cost(), 1e-9);
    }

    @Test
    void noStepUpTwoBlocks() {
        GridCellView view = new GridCellView("#####", "..#..", "..#..", ".....", ".....");
        assertTrue(stepTo(stepsFrom(view, new Node(1, 1, 0)), 2, 3, 0) == null);
    }

    @Test
    void fallsThreeNotFour() {
        GridCellView three = new GridCellView("##", "#.", "#.", "#.", "..", "..");
        assertEquals(
                Move.DESCEND,
                stepTo(stepsFrom(three, new Node(0, 4, 0)), 1, 1, 0).move());

        GridCellView four = new GridCellView("##", "#.", "#.", "#.", "#.", "..", "..");
        assertTrue(stepTo(stepsFrom(four, new Node(0, 5, 0)), 1, 1, 0) == null);
    }

    @Test
    void fallsAnyHeightIntoWater() {
        GridCellView view = new GridCellView("##", "#~", "#~", "#.", "#.", "#.", "#.", "#.", "#.", "..");
        PathStep step = stepTo(stepsFrom(view, new Node(0, 8, 0)), 1, 2, 0);
        assertEquals(Move.DESCEND, step.move());
        assertEquals(COSTS.walk() + COSTS.fall() * 6, step.cost(), 1e-9);
    }

    @Test
    void jumpsOneBlockGap() {
        GridCellView view = new GridCellView("#.#", "...", "...", "...");
        PathStep step = stepTo(stepsFrom(view, new Node(0, 1, 0)), 2, 1, 0);
        assertEquals(Move.JUMP_GAP, step.move());
    }

    @Test
    void doesNotJumpOverWalkableGround() {
        // A running jump is for a gap. Over a walkable cell it is slower than two steps, so no such move is offered.
        GridCellView view = new GridCellView("###", "...", "...", "...");
        assertNull(stepTo(stepsFrom(view, new Node(0, 1, 0)), 2, 1, 0));
    }

    @Test
    void noJumpOverATwoBlockGap() {
        GridCellView view = new GridCellView("#..#", "....", "....", "....");
        assertTrue(stepTo(stepsFrom(view, new Node(0, 1, 0)), 3, 1, 0) == null);
    }

    @Test
    void swimsAndLimitsUnderwaterRun() {
        GridCellView view = new GridCellView("####", "~~~~", "~~~~");
        Node from = new Node(1, 1, 0);

        List<PathStep> fresh = new ArrayList<>();
        new MoveGenerator(view, COSTS, new GoalPoint(99, 99, 99, 0, 0)).neighbours(from, 0, 0, 0, fresh::add);
        PathStep swim = stepTo(fresh, 2, 1, 0);
        assertEquals(Move.SWIM, swim.move());
        assertEquals(COSTS.swim() * MoveGenerator.SUBMERGED_FACTOR, swim.cost(), 1e-9);
        assertEquals(1, swim.headUnderwater());

        List<PathStep> tired = new ArrayList<>();
        new MoveGenerator(view, COSTS, new GoalPoint(99, 99, 99, 0, 0)).neighbours(from, 0, 0, 8, tired::add);
        assertTrue(tired.stream().noneMatch(step -> step.headUnderwater() > 0));
    }

    @Test
    void swimmingOnTheSurfaceIsCheaperThanDiving() {
        GridCellView view = new GridCellView("####", "~~~~", "....");
        PathStep swim = stepTo(stepsFrom(view, new Node(1, 1, 0)), 2, 1, 0);
        assertEquals(Move.SWIM, swim.move());
        assertEquals(COSTS.swim(), swim.cost(), 1e-9);
        assertEquals(0, swim.headUnderwater());
    }

    @Test
    void entersWaterOnceAtItsSurface() {
        // Bank top at y=3, a lake three deep beside it. Walking off the bank enters at the surface; below it the
        // swim moves take over, so no fall may land on a block under the water.
        GridCellView lake = new GridCellView("##", "#~", "#~", "#~", "..", "..");
        List<PathStep> steps = stepsFrom(lake, new Node(0, 4, 0));
        PathStep entry = stepTo(steps, 1, 3, 0);
        assertEquals(Move.DESCEND, entry.move());
        assertEquals(COSTS.walk() + COSTS.fall(), entry.cost(), 1e-9);
        assertNull(stepTo(steps, 1, 2, 0), "fell below the surface");
        assertNull(stepTo(steps, 1, 1, 0), "fell below the surface");
    }

    @Test
    void doesNotFallDeeperInsideWater() {
        // A shelf: water at y=3 drops to water at y=2 beside it. Stepping off is a swim down, not a fall.
        GridCellView shelf = new GridCellView("##", "#~", "~~", "~.", "..", "..");
        PathStep down = stepTo(stepsFrom(shelf, new Node(0, 3, 0)), 1, 2, 0);
        assertEquals(Move.SWIM, down.move());
        assertEquals(COSTS.swim() * Math.sqrt(2.0), down.cost(), 1e-9);
        assertEquals(0, down.headUnderwater());
    }

    @Test
    void risesInWaterBySwimmingNotJumping() {
        // A stepped shore: water at y=2 next to water at y=3. Rising is one swim stroke, never a jump.
        GridCellView stepped = new GridCellView("##", "#~", "~~", ".~", "..", "..");
        PathStep up = stepTo(stepsFrom(stepped, new Node(0, 2, 0)), 1, 3, 0);
        assertEquals(Move.SWIM, up.move());
        assertEquals(COSTS.swim() * Math.sqrt(2.0), up.cost(), 1e-9);
    }

    @Test
    void doesNotLongJumpOutOfWater() {
        // Water at y=1..2, the bank at the same level two cells away. A swimmer has no footing for a running jump.
        GridCellView view = new GridCellView("###", "~~.", "~~.", "...", "...");
        assertNull(stepTo(stepsFrom(view, new Node(0, 1, 0)), 2, 1, 0));
    }

    @Test
    void climbsLadder() {
        GridCellView view = new GridCellView("#", "H", "H", "H", ".");
        PathStep up = stepTo(stepsFrom(view, new Node(0, 1, 0)), 0, 2, 0);
        assertEquals(Move.CLIMB, up.move());
        assertEquals(COSTS.climb(), up.cost(), 1e-9);
    }

    @Test
    void opensWoodenDoor() {
        GridCellView view = new GridCellView("###", ".D.", "...");
        PathStep step = stepTo(stepsFrom(view, new Node(0, 1, 0)), 1, 1, 0);
        assertEquals(Move.DOOR, step.move());
        assertEquals(COSTS.walk() + COSTS.door(), step.cost(), 1e-9);
    }

    @Test
    void avoidsHazard() {
        GridCellView view = new GridCellView("###", ".^.", "...");
        assertTrue(stepTo(stepsFrom(view, new Node(0, 1, 0)), 1, 1, 0) == null);
    }

    @Test
    void penalisesLavaNeighbour() {
        GridCellView view = new GridCellView("###/###", ".../..L", ".../...");
        PathStep step = stepTo(stepsFrom(view, new Node(1, 1, 0)), 2, 1, 0);
        assertFalse(step == null);
        assertEquals(COSTS.walk() + COSTS.lavaNear(), step.cost(), 1e-9);
    }
}
