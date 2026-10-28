package dev.friendai.features.mining;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.features.navigation.pathing.CellView;
import dev.friendai.features.navigation.pathing.CostModel;
import dev.friendai.features.navigation.pathing.Node;
import dev.friendai.features.navigation.pathing.PathStep;
import dev.friendai.testutil.GridCellView;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ExplorationPlannerTest {

    private static final CostModel COSTS = CostModel.DEFAULTS;
    private static final Node START = new Node(7, 1, 7);

    @Test
    void choosesOpenHeadingOverBlockedFacing() {
        GridCellView view = worldWithNorthWall();
        ExplorationPlanner planner = planner(view, START, 1, 0, 3, 20_000);

        assertEquals(ExplorationPlanner.Status.READY, planner.step(Long.MAX_VALUE, System::nanoTime));
        PathStep first = planner.nextStep().orElseThrow();

        assertTrue(first.to().x() != START.x() || first.to().z() != START.z() - 1);
    }

    @Test
    void descendsTowardHint() {
        GridCellView view = stairWorld();
        Node aboveHint = new Node(7, 2, 7);
        ExplorationPlanner planner = planner(view, aboveHint, 1, 0, 4, 20_000);

        assertEquals(ExplorationPlanner.Status.READY, planner.step(Long.MAX_VALUE, System::nanoTime));
        assertTrue(planner.nextStep().orElseThrow().to().y() < aboveHint.y());
    }

    @Test
    void tunnelsAtOrBelowHint() {
        ExplorationPlanner planner = planner(openWorld(), START, START.y(), 0, 4, 20_000);

        assertEquals(ExplorationPlanner.Status.READY, planner.step(Long.MAX_VALUE, System::nanoTime));
        assertEquals(START.y(), planner.nextStep().orElseThrow().to().y());
    }

    @Test
    void tiesKeepHeading() {
        ExplorationPlanner planner = planner(openWorld(), START, START.y(), 2, 8, 20_000);

        assertEquals(ExplorationPlanner.Status.READY, planner.step(Long.MAX_VALUE, System::nanoTime));
        PathStep selected = planner.nextStep().orElseThrow();

        assertEquals(START.x(), selected.to().x());
        assertEquals(START.z() + 1, selected.to().z());
    }

    @Test
    void cannotEscapeOriginalRadius() {
        ExplorationPlanner planner = planner(openWorld(), START, START.y(), 0, 0, 20_000);

        assertEquals(ExplorationPlanner.Status.NO_ROUTE, planner.step(Long.MAX_VALUE, System::nanoTime));
        assertTrue(planner.nextStep().isEmpty());
    }

    @Test
    void zeroBudgetExpandsNothingAndResumeKeepsDecision() {
        ExplorationPlanner uninterrupted = planner(openWorld(), START, START.y(), 0, 5, 20_000);
        assertEquals(ExplorationPlanner.Status.RUNNING, uninterrupted.step(0, () -> 0));
        assertEquals(0, uninterrupted.expanded());
        assertEquals(ExplorationPlanner.Status.READY, uninterrupted.step(Long.MAX_VALUE, System::nanoTime));

        ExplorationPlanner resumed = planner(openWorld(), START, START.y(), 0, 5, 20_000);
        AtomicLong clock = new AtomicLong();
        assertEquals(ExplorationPlanner.Status.RUNNING, resumed.step(1, clock::getAndIncrement));
        while (resumed.step(Long.MAX_VALUE, System::nanoTime) == ExplorationPlanner.Status.RUNNING) {
            // Resume the same bounded search until its shared candidate queue is exhausted.
        }
        assertEquals(uninterrupted.nextStep(), resumed.nextStep());
    }

    @Test
    void allCandidatesShareNodeLimitAndUnsafeHeadingsReturnNoRoute() {
        ExplorationPlanner limited = planner(openWorld(), START, START.y(), 0, 5, 3);
        limited.step(Long.MAX_VALUE, System::nanoTime);
        assertTrue(limited.expanded() <= 3);

        ExplorationPlanner blocked = planner(walledIn(), START, START.y(), 0, 3, 20_000);
        assertEquals(ExplorationPlanner.Status.NO_ROUTE, blocked.step(Long.MAX_VALUE, System::nanoTime));
    }

    private static ExplorationPlanner planner(
            CellView view, Node start, int targetY, int heading, int radius, int maxNodes) {
        return new ExplorationPlanner(
                view, start, targetY, heading, new MiningBounds(start, radius), Set.of(), COSTS, maxNodes);
    }

    private static GridCellView openWorld() {
        return new GridCellView(
                layer(15, 15, '#'), layer(15, 15, '.'), layer(15, 15, '.'), layer(15, 15, '.'), layer(15, 15, '.'));
    }

    private static GridCellView worldWithNorthWall() {
        String[] foot = rows(15, 15, '.');
        String[] head = rows(15, 15, '.');
        foot[6] = replace(foot[6], 7, 'p');
        head[6] = replace(head[6], 7, 'p');
        return new GridCellView(
                layer(15, 15, '#'),
                String.join("/", foot),
                String.join("/", head),
                layer(15, 15, '.'),
                layer(15, 15, '.'));
    }

    private static GridCellView stairWorld() {
        String[] mid = rows(15, 15, '.');
        mid[7] = replace(mid[7], 7, '#');
        return new GridCellView(
                layer(15, 15, '#'), String.join("/", mid), layer(15, 15, '.'), layer(15, 15, '.'), layer(15, 15, '.'));
    }

    private static GridCellView walledIn() {
        String[] feet = rows(15, 15, '.');
        String[] head = rows(15, 15, '.');
        for (int[] d : new int[][] {{0, -1}, {1, 0}, {0, 1}, {-1, 0}}) {
            feet[START.z() + d[1]] = replace(feet[START.z() + d[1]], START.x() + d[0], 'p');
            head[START.z() + d[1]] = replace(head[START.z() + d[1]], START.x() + d[0], 'p');
        }
        return new GridCellView(
                layer(15, 15, '#'),
                String.join("/", feet),
                String.join("/", head),
                layer(15, 15, '.'),
                layer(15, 15, '.'));
    }

    private static String layer(int width, int depth, char fill) {
        return String.join("/", rows(width, depth, fill));
    }

    private static String[] rows(int width, int depth, char fill) {
        String[] rows = new String[depth];
        for (int z = 0; z < depth; z++) {
            rows[z] = String.valueOf(fill).repeat(width);
        }
        return rows;
    }

    private static String replace(String row, int x, char value) {
        return row.substring(0, x) + value + row.substring(x + 1);
    }
}
