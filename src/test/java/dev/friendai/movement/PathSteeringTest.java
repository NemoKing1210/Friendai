package dev.friendai.movement;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.friendai.pathing.Move;
import dev.friendai.pathing.Node;
import dev.friendai.pathing.PathStep;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PathSteeringTest {

    private static PathStep step(int fx, int fy, int fz, int tx, int ty, int tz, Move move) {
        return new PathStep(new Node(fx, fy, fz), new Node(tx, ty, tz), move, List.of(), List.of(), 4.6, 0);
    }

    private static List<PathStep> eastThenNorth() {
        List<PathStep> steps = new ArrayList<>();
        steps.add(step(0, 2, 0, 1, 2, 0, Move.WALK));
        steps.add(step(1, 2, 0, 2, 2, 0, Move.WALK));
        steps.add(step(2, 2, 0, 2, 2, 1, Move.WALK));
        steps.add(step(2, 2, 1, 2, 2, 2, Move.WALK));
        return steps;
    }

    @Test
    void holdsTheHeadingWhenAsked() {
        assertEquals(-90.0f, PathSteering.yaw(eastThenNorth(), 0, 0.5, 0.5, -90.0f, true), 1e-4);
    }

    @Test
    void aimsPastADiagonalLandingCell() {
        List<PathStep> steps = List.of(
                step(0, 2, 0, 1, 1, 0, Move.DESCEND),
                step(1, 1, 0, 2, 1, 0, Move.WALK),
                step(2, 1, 0, 3, 1, 0, Move.WALK));
        // The landing cell is below and one block east; the route continues east, so the aim stays east.
        assertEquals(-90.0f, PathSteering.yaw(steps, 0, 0.5, 0.5, -90.0f, false), 1e-4);
    }

    @Test
    void turnsAtMostThirtyDegreesTowardAReversal() {
        List<PathStep> steps = List.of(step(0, 2, 0, 0, 2, -1, Move.WALK),
                step(0, 2, -1, 0, 2, -2, Move.WALK));
        float yaw = PathSteering.yaw(steps, 0, 0.5, 0.5, 0.0f, false);
        assertEquals(30.0f, Facing.difference(yaw, 0.0f), 1e-4);
    }

    @Test
    void turnsTheLastFewDegreesToTheAim() {
        PathStep askew = step(0, 2, 0, 1, 2, 0, Move.WALK);
        float yaw = PathSteering.yaw(List.of(askew), 0, 0.5, 0.5, -86.0f, false);
        assertEquals(-90.0f, yaw, 1e-4);
    }

    @Test
    void stopsAtACornerInsteadOfCuttingIt() {
        List<PathStep> steps = List.of(step(0, 2, 0, 1, 2, 0, Move.WALK),
                step(1, 2, 0, 1, 2, 1, Move.WALK),
                step(1, 2, 1, 1, 2, 2, Move.WALK));
        // A 1.5-block look-ahead would land halfway up the north leg; the corner node wins.
        assertEquals(-90.0f, PathSteering.yaw(steps, 0, 0.5, 0.5, -90.0f, false), 1e-4);
    }

    @Test
    void holdsTheHeadingWhenTheCurrentNodeIsBehind() {
        List<PathStep> steps = List.of(step(0, 2, 0, 1, 2, 0, Move.WALK),
                step(1, 2, 0, 2, 2, 0, Move.WALK),
                step(2, 2, 0, 3, 2, 0, Move.WALK));
        // Node 1 is only 0.2 blocks away and behind the body: there is no direction to aim at, so it keeps east.
        float yaw = PathSteering.yaw(steps, 0, 1.7, 0.5, -90.0f, false);
        assertEquals(-90.0f, yaw, 1e-4);
    }

    @Test
    void ignoresAnAimInsideTheMinimumDistance() {
        List<PathStep> steps = List.of(step(0, 2, 0, 1, 2, 0, Move.WALK));
        float yaw = PathSteering.yaw(steps, 0, 1.4, 0.5, 0.0f, false);
        assertEquals(0.0f, yaw, 1e-4);
    }
}
