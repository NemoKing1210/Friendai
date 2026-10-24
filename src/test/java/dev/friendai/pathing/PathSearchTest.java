package dev.friendai.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.testutil.GridCellView;
import java.util.List;
import org.junit.jupiter.api.Test;

class PathSearchTest {

    private static final CostModel COSTS = CostModel.DEFAULTS;

    private static PathSearch search(GridCellView view, Node start, PathGoal goal, int maxNodes, int placeable) {
        return new PathSearch(view, COSTS, goal, start, new SearchOptions(maxNodes, 64, placeable, 1));
    }

    private static SearchResult run(PathSearch search) {
        SearchResult result = search.step(Long.MAX_VALUE, System::nanoTime);
        return result;
    }

    private static Path path(GridCellView view, Node start, PathGoal goal, int placeable) {
        SearchResult result = run(search(view, start, goal, 20_000, placeable));
        assertEquals(SearchResult.Status.FOUND, result.status());
        return result.path();
    }

    private static boolean hasMove(Path path, Move move) {
        return path.steps().stream().anyMatch(step -> step.move() == move);
    }

    private static GoalPoint point(double x, double y, double z) {
        return new GoalPoint(x, y, z, 0.5, 0.5, COSTS.walk());
    }

    /** A floor with a wall across it at {@code wallX}, open at one row of {@code z}. */
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

    @Test
    void flatWalkIsShortest() {
        GridCellView view = new GridCellView("##########", "..........", "..........");
        Path path = path(view, new Node(0, 1, 0), point(5.5, 1, 0.5), 0);
        assertEquals(5, path.steps().size());
        assertTrue(path.steps().stream().allMatch(step -> step.move() == Move.WALK));
    }

    @Test
    void stoneWallDugWhenDetourLong() {
        GridCellView view = wallGrid(20, 14, 10, 13, '#');
        Path path = path(view, new Node(1, 1, 0), point(18.5, 1, 0.5), 0);
        assertTrue(hasMove(path, Move.DIG) || hasMove(path, Move.DIG_ASCEND));
    }

    @Test
    void stoneWallWalkedAroundWhenDetourShort() {
        GridCellView view = wallGrid(20, 14, 10, 1, '#');
        Path path = path(view, new Node(1, 1, 0), point(18.5, 1, 0.5), 0);
        assertFalse(hasMove(path, Move.DIG));
    }

    @Test
    void plankWallOnlyDetour() {
        GridCellView view = wallGrid(20, 14, 10, 13, 'p');
        Path path = path(view, new Node(1, 1, 0), point(18.5, 1, 0.5), 0);
        assertFalse(hasMove(path, Move.DIG));
    }

    @Test
    void pitBridgedWhenDetourLong() {
        GridCellView pit = pit(3, 4);
        Path path = path(pit, new Node(1, 1, 0), point(10.5, 1, 0.5), 3);
        assertEquals(3, path.steps().stream().filter(step -> step.move() == Move.BRIDGE).count());
        assertEquals(3, path.places());
    }

    /** A floor with a pit {@code pitWidth} wide from x = 5, {@code pitDepth} rows deep from z = 0. */
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

    @Test
    void pitWalkedAroundWhenDetourShort() {
        GridCellView view = pit(3, 1);
        Path path = path(view, new Node(1, 1, 0), point(10.5, 1, 0.5), 3);
        assertEquals(0, path.places());
        assertFalse(hasMove(path, Move.BRIDGE));
    }

    @Test
    void pitWithoutBlocksIsNoPath() {
        GridCellView view = pit(3, 4);
        assertEquals(SearchResult.Status.NO_PATH,
                run(search(view, new Node(1, 1, 0), point(10.5, 1, 0.5), 20_000, 0)).status());

        Path path = path(view, new Node(1, 1, 0), point(10.5, 1, 0.5), Integer.MAX_VALUE);
        assertEquals(3, path.places());
    }

    @Test
    void buriedTargetReachedByTunnel() {
        GridCellView view = new GridCellView(
                "##########",
                "...ddd....",
                "...d#d....",
                "...ddd....",
                "..........");
        GoalReach goal = new GoalReach(List.of(new Pos(4, 2, 0)), 4.0, 1.62, COSTS.walk());
        Path path = path(view, new Node(2, 1, 0), goal, 0);
        assertTrue(hasMove(path, Move.DIG));
        assertFalse(path.steps().stream().anyMatch(step -> step.breaks().contains(new Pos(4, 2, 0))));
    }

    @Test
    void treeTopReachedByPillar() {
        String[] layers = new String[12];
        layers[0] = "######";
        for (int y = 1; y <= 8; y++) {
            layers[y] = "...l..";
        }
        for (int y = 9; y < 12; y++) {
            layers[y] = "......";
        }
        GridCellView view = new GridCellView(layers);
        GoalReach goal = new GoalReach(List.of(new Pos(3, 8, 0)), 4.0, 1.62, COSTS.walk());
        Path path = path(view, new Node(1, 1, 0), goal, 16);
        assertTrue(path.places() >= 3);
        assertTrue(hasMove(path, Move.PILLAR));
        assertFalse(path.steps().stream().anyMatch(step -> step.breaks().contains(new Pos(3, 8, 0))));
    }

    @Test
    void cheaperOfSeveralTargetsWins() {
        // A free corridor along z = 1 with the exposed target at its far end; a dirt wall at (5, 1..2, 2)
        // hides a second target just behind it.
        String[] layers = {
                floorRows(10, 4),
                String.join("/", "..........", "..........", ".....d....", ".........."),
                String.join("/", "..........", "..........", ".....d....", ".........."),
                String.join("/", "..........", "..........", "..........", "..........")
        };
        GridCellView view = new GridCellView(layers);
        GoalReach goal = new GoalReach(List.of(new Pos(5, 1, 3), new Pos(0, 1, 1)), 4.0, 1.62, COSTS.walk());
        Path path = path(view, new Node(5, 1, 1), goal, 0);
        assertEquals(0, path.breaks());
    }

    private static String floorRows(int width, int depth) {
        StringBuilder layer = new StringBuilder();
        for (int z = 0; z < depth; z++) {
            if (z > 0) {
                layer.append('/');
            }
            layer.append("#".repeat(width));
        }
        return layer.toString();
    }

    @Test
    void stepResumesAcrossCalls() {
        GridCellView view = wallGrid(20, 14, 10, 13, '#');
        GoalPoint goal = point(18.5, 1, 0.5);

        PathSearch incremental = search(view, new Node(1, 1, 0), goal, 20_000, 0);
        assertEquals(SearchResult.Status.RUNNING, incremental.step(0, System::nanoTime).status());
        SearchResult result = incremental.step(Long.MAX_VALUE, System::nanoTime);
        assertEquals(SearchResult.Status.FOUND, result.status());

        PathSearch once = search(view, new Node(1, 1, 0), goal, 20_000, 0);
        assertEquals(once.step(Long.MAX_VALUE, System::nanoTime).path().cost(), result.path().cost(), 1e-9);
    }

    @Test
    void nodeLimitGivesPartialCloser() {
        GridCellView view = new GridCellView("####################", "....................", "....................");
        GoalPoint goal = point(19.5, 1, 0.5);
        SearchResult result = run(search(view, new Node(0, 1, 0), goal, 5, 0));
        assertEquals(SearchResult.Status.PARTIAL, result.status());
        assertNotNull(result.path());
        assertTrue(result.path().partial());
        double startHeuristic = goal.heuristic(new Node(0, 1, 0));
        Node end = result.path().steps().get(result.path().steps().size() - 1).to();
        assertTrue(goal.heuristic(end) < startHeuristic);
    }

    @Test
    void unloadedIsImpassable() {
        GridCellView view = new GridCellView("#########", "...?...", "........");
        assertEquals(SearchResult.Status.NO_PATH,
                run(search(view, new Node(0, 1, 0), point(6.5, 1, 0.5), 20_000, 0)).status());
    }

    @Test
    void startsInWater() {
        GridCellView view = new GridCellView("####", "~~..", "....");
        Path path = path(view, new Node(0, 1, 0), point(3.5, 1, 0.5), 0);
        assertTrue(hasMove(path, Move.SWIM));
    }

    @Test
    void startsOnLadder() {
        GridCellView view = new GridCellView("##", "H.", "..");
        Path path = path(view, new Node(1, 1, 0), point(0.5, 1, 0.5), 0);
        assertEquals(1, path.steps().size());
    }

    @Test
    void escapesPitByPillar() {
        GridCellView view = pitWorld();
        Path path = path(view, new Node(2, 1, 0), new GoalPoint(2.5, 4, 0.5, 1.5, 0.5, COSTS.walk()), 3);
        assertTrue(path.steps().stream().anyMatch(step -> step.move() == Move.PILLAR));
        assertTrue(path.places() >= 2 && path.places() <= 3);
        assertEquals(4, path.end().y());
    }

    @Test
    void escapesPitByDigging() {
        GridCellView view = pitWorld();
        Path path = path(view, new Node(2, 1, 0), new GoalPoint(2.5, 4, 0.5, 1.5, 0.5, COSTS.walk()), 0);
        assertTrue(hasMove(path, Move.DIG_ASCEND));
    }

    /** A 1×1 pit three deep in flat ground, the surface at y = 4. */
    private static GridCellView pitWorld() {
        String[] layers = {
                "########",
                "##.###..",
                "##.###..",
                "##.###..",
                "........",
                "........",
                "........"
        };
        return new GridCellView(layers);
    }
}
