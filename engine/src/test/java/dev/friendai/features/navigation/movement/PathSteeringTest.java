package dev.friendai.features.navigation.movement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.features.navigation.pathing.Move;
import dev.friendai.features.navigation.pathing.Node;
import dev.friendai.features.navigation.pathing.PathStep;
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
        List<PathStep> steps = List.of(step(0, 2, 0, 0, 2, -1, Move.WALK), step(0, 2, -1, 0, 2, -2, Move.WALK));
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
        List<PathStep> steps = List.of(
                step(0, 2, 0, 1, 2, 0, Move.WALK),
                step(1, 2, 0, 1, 2, 1, Move.WALK),
                step(1, 2, 1, 1, 2, 2, Move.WALK));
        // A 1.5-block look-ahead would land halfway up the north leg; the corner node wins.
        assertEquals(-90.0f, PathSteering.yaw(steps, 0, 0.5, 0.5, -90.0f, false), 1e-4);
    }

    @Test
    void holdsTheHeadingWhenTheCurrentNodeIsBehind() {
        List<PathStep> steps = List.of(
                step(0, 2, 0, 1, 2, 0, Move.WALK),
                step(1, 2, 0, 2, 2, 0, Move.WALK),
                step(2, 2, 0, 3, 2, 0, Move.WALK));
        // Node 1 is only 0.2 blocks away and behind the body: there is no direction to aim at, so it keeps east.
        float yaw = PathSteering.yaw(steps, 0, 1.7, 0.5, -90.0f, false);
        assertEquals(-90.0f, yaw, 1e-4);
    }

    private static List<PathStep> eastRunThenSouth() {
        return List.of(
                step(0, 2, 0, 6, 2, 0, Move.WALK),
                step(6, 2, 0, 6, 2, 6, Move.WALK),
                step(6, 2, 6, 6, 2, 12, Move.WALK));
    }

    @Test
    void runsFullSpeedFarFromACorner() {
        assertEquals(1.0, PathSteering.speed(eastRunThenSouth(), 0, 0.5, 0.5), 1e-9);
    }

    @Test
    void slowsForARightAngleCornerAndMoreTheCloserItGets() {
        List<PathStep> steps = eastRunThenSouth();
        double far = PathSteering.speed(steps, 0, 3.5, 0.5);
        double near = PathSteering.speed(steps, 0, 5.0, 0.5);
        double at = PathSteering.speed(steps, 0, 6.3, 0.5);
        assertTrue(far > near && near > at, far + " " + near + " " + at);
        assertTrue(at <= 0.55, "the corner speed was " + at);
        assertTrue(at >= PathSteering.MIN_SPEED);
    }

    @Test
    void aGentlerCornerSlowsLessThanASharpOne() {
        List<PathStep> diagonal =
                List.of(step(0, 2, 0, 6, 2, 0, Move.WALK), step(6, 2, 0, 12, 2, 6, Move.WALK_DIAGONAL));
        List<PathStep> square = eastRunThenSouth();
        assertTrue(PathSteering.speed(diagonal, 0, 6.3, 0.5) > PathSteering.speed(square, 0, 6.3, 0.5));
    }

    @Test
    void slowsForTheEndOfTheRoute() {
        List<PathStep> steps = List.of(step(0, 2, 0, 8, 2, 0, Move.WALK));
        assertEquals(1.0, PathSteering.speed(steps, 0, 0.5, 0.5), 1e-9);
        assertTrue(PathSteering.speed(steps, 0, 8.0, 0.5) <= 0.55);
    }

    @Test
    void keepsItsRunUpTowardAGapAlongTheSameLine() {
        List<PathStep> steps = List.of(step(0, 2, 0, 4, 2, 0, Move.WALK), step(4, 2, 0, 6, 2, 0, Move.JUMP_GAP));
        assertEquals(1.0, PathSteering.speed(steps, 0, 4.4, 0.5), 1e-9);
        assertEquals(1.0, PathSteering.speed(steps, 1, 4.9, 0.5), 1e-9);
    }

    @Test
    void ignoresAnAimInsideTheMinimumDistance() {
        List<PathStep> steps = List.of(step(0, 2, 0, 1, 2, 0, Move.WALK));
        float yaw = PathSteering.yaw(steps, 0, 1.4, 0.5, 0.0f, false);
        assertEquals(0.0f, yaw, 1e-4);
    }
}
