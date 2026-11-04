package dev.friendai.features.navigation.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.foundation.spatial.Move;
import dev.friendai.foundation.spatial.Node;
import dev.friendai.foundation.spatial.Path;
import dev.friendai.foundation.spatial.PathStep;
import dev.friendai.foundation.spatial.Pos;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PathTrimTest {

    private static Path line(int length, boolean partial) {
        List<PathStep> steps = new ArrayList<>();
        double cost = 0;
        for (int i = 0; i < length; i++) {
            steps.add(new PathStep(
                    new Node(i, 1, 0),
                    new Node(i + 1, 1, 0),
                    Move.WALK,
                    List.of(),
                    i == 2 ? List.of(new Pos(3, 0, 0)) : List.of(),
                    4.6,
                    0));
            cost += 4.6;
        }
        return new Path(steps, cost, 0, 1, partial);
    }

    @Test
    void startingAtTheFirstNodeKeepsTheRoute() {
        Path path = line(6, false);
        assertSame(path, path.afterNode(new Node(0, 1, 0), 4));
    }

    @Test
    void standingAtALaterNodeDropsTheStepsBehind() {
        Path path = line(6, true);
        Path trimmed = path.afterNode(new Node(2, 1, 0), 4);
        assertEquals(4, trimmed.steps().size());
        assertEquals(new Node(2, 1, 0), trimmed.steps().get(0).from());
        assertEquals(path.end(), trimmed.end());
        assertTrue(trimmed.partial());
        assertEquals(4 * 4.6, trimmed.cost(), 1e-9);
    }

    @Test
    void droppedStepsTakeTheirPlacementsWithThem() {
        Path path = line(6, false);
        assertEquals(1, path.places());
        assertEquals(0, path.afterNode(new Node(3, 1, 0), 4).places());
        assertEquals(1, path.afterNode(new Node(1, 1, 0), 4).places());
    }

    @Test
    void theLatestMatchingStepWins() {
        List<PathStep> steps = List.of(
                new PathStep(new Node(0, 1, 0), new Node(1, 1, 0), Move.WALK, List.of(), List.of(), 1, 0),
                new PathStep(new Node(1, 1, 0), new Node(0, 1, 0), Move.WALK, List.of(), List.of(), 1, 0),
                new PathStep(new Node(0, 1, 0), new Node(0, 1, 1), Move.WALK, List.of(), List.of(), 1, 0));
        Path trimmed = new Path(steps, 3, 0, 0, false).afterNode(new Node(0, 1, 0), 4);
        assertEquals(1, trimmed.steps().size());
    }

    @Test
    void aNodeBeyondTheLookaheadOrOffTheRouteChangesNothing() {
        Path path = line(8, false);
        assertSame(path, path.afterNode(new Node(6, 1, 0), 3));
        assertSame(path, path.afterNode(new Node(0, 1, 5), 4));
    }

    @Test
    void standingOnTheLastNodeLeavesAnEmptyRoute() {
        Path path = line(3, false);
        Path trimmed = path.afterNode(new Node(3, 1, 0), 4);
        assertTrue(trimmed.steps().isEmpty());
    }

    private static PathStep leg(Node from, Node to, double cost) {
        return new PathStep(from, to, Move.WALK, List.of(), List.of(), cost, 0);
    }

    private static Path route(PathStep... steps) {
        double cost = 0;
        int breaks = 0;
        int places = 0;
        for (PathStep step : steps) {
            cost += step.cost();
            breaks += step.breaks().size();
            places += step.places().size();
        }
        return new Path(List.of(steps), cost, breaks, places, false);
    }

    @Test
    void aPositionBetweenTwoNodesKeepsTheLegItIsWalking() {
        Path path = route(
                leg(new Node(0, 1, 0), new Node(1, 1, 0), 1),
                leg(new Node(1, 1, 0), new Node(6, 1, 0), 5),
                leg(new Node(6, 1, 0), new Node(6, 1, 1), 1));
        Path trimmed = path.afterPosition(new Pos(3, 1, 0), 4);
        assertEquals(2, trimmed.steps().size());
        assertEquals(new Node(1, 1, 0), trimmed.steps().get(0).from());
        assertEquals(path.end(), trimmed.end());
        assertEquals(6, trimmed.cost(), 1e-9);
    }

    @Test
    void standingOnAnIntermediateNodeDropsTheStepsBeforeIt() {
        Path path = line(6, true);
        Path trimmed = path.afterPosition(new Pos(2, 1, 0), 4);
        assertEquals(4, trimmed.steps().size());
        assertEquals(new Node(2, 1, 0), trimmed.steps().get(0).from());
        assertEquals(path.end(), trimmed.end());
        assertTrue(trimmed.partial());
        assertEquals(4 * 4.6, trimmed.cost(), 1e-9);
    }

    @Test
    void aTrimmedRouteLosesThePlacementsItDropped() {
        Path path = line(6, false);
        assertEquals(0, path.afterPosition(new Pos(3, 1, 0), 4).places());
        assertEquals(1, path.afterPosition(new Pos(1, 1, 0), 4).places());
    }

    @Test
    void aPositionOffTheRouteBeyondTheCorridorChangesNothing() {
        Path path = line(6, false);
        assertSame(path, path.afterPosition(new Pos(3, 1, 3), 4));
        assertSame(path, path.afterPosition(new Pos(2, 2, 0), 4));
    }

    @Test
    void aDiagonalDriftInsideTheCorridorStillCounts() {
        Path path = route(leg(new Node(0, 1, 0), new Node(1, 1, 0), 1), leg(new Node(1, 1, 0), new Node(2, 2, 0), 1));
        Path trimmed = path.afterPosition(new Pos(1, 2, 0), 4);
        assertEquals(1, trimmed.steps().size());
        assertEquals(new Node(1, 1, 0), trimmed.steps().get(0).from());
    }

    @Test
    void aPositionOnALaterLegDoesNotSkipTheStepsBeforeIt() {
        Path path = route(
                leg(new Node(0, 1, 0), new Node(4, 1, 0), 4),
                leg(new Node(4, 1, 0), new Node(4, 2, 0), 1),
                leg(new Node(4, 2, 0), new Node(0, 2, 0), 4),
                leg(new Node(0, 2, 0), new Node(0, 1, 0), 1),
                leg(new Node(0, 1, 0), new Node(4, 1, 0), 4));
        assertSame(path, path.afterPosition(new Pos(2, 1, 0), 8));
    }

    @Test
    void aPositionBeyondTheLookaheadChangesNothing() {
        Path path = line(8, false);
        assertSame(path, path.afterPosition(new Pos(5, 1, 0), 4));
        Path trimmed = path.afterPosition(new Pos(5, 1, 0), 5);
        assertEquals(3, trimmed.steps().size());
        assertEquals(new Node(5, 1, 0), trimmed.steps().get(0).from());
    }

    @Test
    void noProgressReturnsTheSameRoute() {
        Path path = line(6, false);
        assertSame(path, path.afterPosition(new Pos(0, 1, 0), 4));
        assertSame(path, path.afterPosition(new Pos(2, 1, 0), 0));
        Path empty = route();
        assertSame(empty, empty.afterPosition(new Pos(2, 1, 0), 8));
    }

    @Test
    void theSamePositionTrimsTheSameWayEveryTime() {
        Path path = line(6, false);
        assertEquals(path.afterPosition(new Pos(2, 1, 0), 4), path.afterPosition(new Pos(2, 1, 0), 4));
    }
}
