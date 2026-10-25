package dev.friendai.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.friendai.testutil.GridCellView;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import org.junit.jupiter.api.Test;

class PathOptimalityTest {
    @Test
    void pointPathsMatchDijkstra() {
        assertOptimal("flat", new GridCellView("########", "........", "........"), new Node(1, 1, 0), point(7.5, 1, 0.5), 0);
        assertOptimal("wall with opening", wallGrid(10, 6, 5, 5, 'p'), new Node(1, 1, 0), point(8.5, 1, 0.5), 0);
        assertOptimal("wall to dig", wallGrid(10, 6, 5, 5, '#'), new Node(1, 1, 0), point(8.5, 1, 0.5), 0);
        assertOptimal("pit with material", pit(2, 3), new Node(1, 1, 0), point(8.5, 1, 0.5), 3);
        assertOptimal("step up",
                new GridCellView("######", "..####", "......", "......"),
                new Node(1, 1, 0), point(3.5, 2, 0.5), 0);
        assertOptimal("ladder",
                new GridCellView("#####", "..H..", "..H..", "..H..", ".....", "....."),
                new Node(1, 1, 0), point(2.5, 4, 0.5), 0);
    }

    @Test
    void reachPathsMatchDijkstra() {
        GridCellView view = wallGrid(10, 6, 5, 5, '#');
        assertOptimal("reach", view, new Node(1, 1, 0),
                new GoalReach(List.of(new Pos(8, 1, 0)), 2, 1.62), 0);
    }

    @Test
    void waterSlabsAndDiagonalLedgesMatchDijkstra() {
        assertOptimal("water", new GridCellView("########/########/########",
                "...~~~../...~~~../...~~~..", "...~~~../...~~~../...~~~..", "......../......../........"),
                new Node(1, 1, 1), point(7.5, 1, 1.5), 0);
        assertOptimal("slab", new GridCellView("########", "...__...", "........", "........"),
                new Node(1, 1, 0), point(4.5, 2, 0.5), 0);
        assertOptimal("diagonal ledge", new GridCellView("####/####/####/####",
                "..../..../..##/..##", "..../..../..../....", "..../..../..../...."),
                new Node(1, 1, 1), point(3.5, 2, 3.5), 0);
    }

    @Test
    void seededMapsMatchDijkstraForPointAndReachGoals() {
        Random random = new Random(0xF12EAD);
        for (int sample = 0; sample < 32; sample++) {
            StringBuilder obstacles = new StringBuilder();
            for (int z = 0; z < 6; z++) {
                if (z > 0) {
                    obstacles.append('/');
                }
                for (int x = 0; x < 8; x++) {
                    boolean endpoint = (x == 0 && z == 0) || (x == 7 && z == 5);
                    obstacles.append(!endpoint && random.nextInt(4) == 0 ? 'd' : '.');
                }
            }
            String floor = String.join("/", Collections.nCopies(6, "########"));
            String air = String.join("/", Collections.nCopies(6, "........"));
            GridCellView view = new GridCellView(floor, obstacles.toString(), obstacles.toString(), air);
            assertOptimal("point seed " + sample, view, new Node(0, 1, 0), point(7.5, 1, 5.5), 0);
            assertOptimal("reach seed " + sample, view, new Node(0, 1, 0),
                    new GoalReach(List.of(new Pos(7, 1, 5)), 2, 1.62), 0);
        }
    }

    private record State(Node node, int placed, int underwater) {}

    private record Entry(State state, double cost) {}

    private static void assertOptimal(String label, GridCellView view, Node start, PathGoal goal, int placeable) {
        double expected = dijkstra(view, start, goal, placeable);
        SearchResult result = new PathSearch(view, CostModel.DEFAULTS, goal, start,
                new SearchOptions(100_000, 64, placeable, 1)).step(Long.MAX_VALUE, System::nanoTime);
        assertEquals(SearchResult.Status.FOUND, result.status(), label);
        assertEquals(expected, result.path().cost(), 1e-9, label);
    }

    private static double dijkstra(GridCellView view, Node start, PathGoal goal, int placeable) {
        Map<State, Double> costs = new HashMap<>();
        PriorityQueue<Entry> queue = new PriorityQueue<>(Comparator.comparingDouble(Entry::cost));
        State first = new State(start, 0, 0);
        costs.put(first, 0.0);
        queue.add(new Entry(first, 0));
        MoveGenerator moves = new MoveGenerator(view, CostModel.DEFAULTS, goal);
        while (!queue.isEmpty()) {
            Entry current = queue.remove();
            if (current.cost() > costs.get(current.state())) {
                continue;
            }
            State state = current.state();
            if (goal.isGoal(state.node(), view)) {
                return current.cost();
            }
            moves.neighbours(state.node(), state.placed(), placeable, state.underwater(), step -> {
                int placed = state.placed() + step.places().size();
                if (placed > placeable) {
                    return;
                }
                State next = new State(step.to(), placed, step.headUnderwater());
                double cost = current.cost() + step.cost();
                if (cost < costs.getOrDefault(next, Double.POSITIVE_INFINITY)) {
                    costs.put(next, cost);
                    queue.add(new Entry(next, cost));
                }
            });
        }
        throw new AssertionError("reference could not reach " + goal);
    }

    private static GoalPoint point(double x, double y, double z) {
        return new GoalPoint(x, y, z, 0.5, 0.5);
    }

    private static GridCellView wallGrid(int width, int depth, int wallX, int openingZ, char wall) {
        String[] layers = new String[4];
        for (int y = 0; y < layers.length; y++) {
            StringBuilder layer = new StringBuilder();
            for (int z = 0; z < depth; z++) {
                if (z > 0) {
                    layer.append('/');
                }
                for (int x = 0; x < width; x++) {
                    char c = '.';
                    if (y == 0) {
                        c = '#';
                    } else if (y <= 2 && wallX >= 0 && x == wallX && z != openingZ) {
                        c = wall;
                    }
                    layer.append(c);
                }
            }
            layers[y] = layer.toString();
        }
        return new GridCellView(layers);
    }

    private static GridCellView pit(int pitWidth, int pitDepth) {
        int width = 12;
        int depth = 4;
        String[] layers = new String[3];
        for (int y = 0; y < layers.length; y++) {
            StringBuilder layer = new StringBuilder();
            for (int z = 0; z < depth; z++) {
                if (z > 0) {
                    layer.append('/');
                }
                for (int x = 0; x < width; x++) {
                    boolean hole = y == 0 && z < pitDepth && x >= 5 && x < 5 + pitWidth;
                    layer.append(hole ? '.' : (y == 0 ? '#' : '.'));
                }
            }
            layers[y] = layer.toString();
        }
        return new GridCellView(layers);
    }
}
