package dev.friendai.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.friendai.testutil.GridCellView;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PathSmootherTest {

    private static PathStep walk(int fx, int fz, int tx, int tz) {
        return new PathStep(new Node(fx, 1, fz), new Node(tx, 1, tz), Move.WALK, List.of(), List.of(), 4.6, 0);
    }

    private static Path path(List<PathStep> steps) {
        double cost = 0.0;
        int places = 0;
        int breaks = 0;
        for (PathStep step : steps) {
            cost += step.cost();
            places += step.places().size();
            breaks += step.breaks().size();
        }
        return new Path(steps, cost, breaks, places, false);
    }

    private static List<PathStep> eastRun(int length) {
        List<PathStep> steps = new ArrayList<>();
        for (int i = 0; i < length; i++) {
            steps.add(walk(i, 0, i + 1, 0));
        }
        return steps;
    }

    private static final GridCellView FLOOR = new GridCellView(
            "##########/##########/##########",
            "........../........../..........",
            "........../........../..........");

    @Test
    void collapsesAStraightRunIntoOneLeg() {
        Path merged = PathSmoother.simplify(path(eastRun(5)), FLOOR);
        assertEquals(1, merged.steps().size());
        assertEquals(new Node(0, 1, 0), merged.steps().get(0).from());
        assertEquals(new Node(5, 1, 0), merged.steps().get(0).to());
        assertEquals(23.0, merged.cost(), 1e-9);
        assertEquals(0, merged.places());
    }

    @Test
    void aPlacementInTheMiddleStaysItsOwnStep() {
        List<PathStep> steps = eastRun(5);
        steps.set(2, new PathStep(new Node(2, 1, 0), new Node(3, 1, 0), Move.WALK, List.of(),
                List.of(new Pos(3, 0, 0)), 4.6, 0));
        Path merged = PathSmoother.simplify(path(steps), FLOOR);
        assertEquals(3, merged.steps().size());
        assertEquals(1, merged.places());
        assertEquals(new Node(0, 1, 0), merged.steps().get(0).from());
        assertEquals(new Node(2, 1, 0), merged.steps().get(0).to());
        assertEquals(new Node(3, 1, 0), merged.steps().get(2).from());
        assertEquals(new Node(5, 1, 0), merged.steps().get(2).to());
    }

    @Test
    void aBlockedIntermediateNodeStopsTheRun() {
        GridCellView blocked = new GridCellView("##########", "...#......", "..........");
        Path merged = PathSmoother.simplify(path(eastRun(5)), blocked);
        assertEquals(3, merged.steps().size());
        assertEquals(new Node(2, 1, 0), merged.steps().get(0).to());
        assertEquals(new Node(2, 1, 0), merged.steps().get(1).from());
        assertEquals(new Node(3, 1, 0), merged.steps().get(1).to());
        assertEquals(new Node(5, 1, 0), merged.steps().get(2).to());
    }

    @Test
    void aDirectionChangeEndsTheRun() {
        List<PathStep> steps = new ArrayList<>(eastRun(2));
        steps.add(walk(2, 0, 2, 1));
        steps.add(walk(2, 1, 2, 2));
        Path merged = PathSmoother.simplify(path(steps), FLOOR);
        assertEquals(2, merged.steps().size());
        assertEquals(new Node(2, 1, 0), merged.steps().get(0).to());
        assertEquals(new Node(2, 1, 2), merged.steps().get(1).to());
    }

    @Test
    void aSingleStepIsReturnedUnchanged() {
        Path path = path(eastRun(1));
        assertSame(path, PathSmoother.simplify(path, FLOOR));
    }
}
