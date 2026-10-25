package dev.friendai.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PathTrimTest {

    private static Path line(int length, boolean partial) {
        List<PathStep> steps = new ArrayList<>();
        double cost = 0;
        for (int i = 0; i < length; i++) {
            steps.add(new PathStep(new Node(i, 1, 0), new Node(i + 1, 1, 0), Move.WALK, List.of(),
                    i == 2 ? List.of(new Pos(3, 0, 0)) : List.of(), 4.6, 0));
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
}
