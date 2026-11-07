package dev.friendai.features.mining;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.features.mining.excavation.ExcavationGeometry;
import dev.friendai.foundation.spatial.Cell;
import dev.friendai.foundation.spatial.CellView;
import dev.friendai.foundation.spatial.CostModel;
import dev.friendai.foundation.spatial.Node;
import dev.friendai.foundation.spatial.PathSearch;
import dev.friendai.foundation.spatial.PathStep;
import dev.friendai.foundation.spatial.Pos;
import dev.friendai.foundation.spatial.SearchOptions;
import dev.friendai.foundation.spatial.SearchResult;
import dev.friendai.testutil.GridCellView;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The session's own plan loop over a solid hillside: at every step it either breaks a target from where it stands or
 * walks a planned route, exactly as {@code MiningSession} does. A descending stair must be carved inside its own
 * cross-section; a plan that opens any other cell means the companion tours side stances instead of walking the stair.
 */
class MiningStairCarveTest {

    private static final int WIDTH = 13;
    private static final int HEIGHT = 12;
    private static final int DEPTH = 7;
    private static final Node START = new Node(2, 8, 2);

    @Test
    void aStairIsCarvedInsideItsOwnCrossSection() {
        GridCellView terrain = hillside();
        Set<Pos> broken = new HashSet<>();
        Set<Pos> supports = new HashSet<>(Set.of(START.support()));
        List<Pos> stair = stairCells(6);
        Node stance = START;
        List<String> trace = new ArrayList<>();
        int guard = 0;

        while (true) {
            if (++guard > 40) {
                throw new AssertionError("the carve did not finish; trace:\n" + String.join("\n", trace));
            }
            List<Pos> remaining = new ArrayList<>();
            for (Pos cell : stair) {
                if (!broken.contains(cell)) {
                    remaining.add(cell);
                }
            }
            if (remaining.isEmpty()) {
                break;
            }
            CellView live = live(terrain, broken);
            MiningGoal goal = new MiningGoal(remaining, 4.0, 1.62, supports, Set.of(), false, true);
            MiningPathPolicy policy =
                    new MiningPathPolicy(live, new MiningBounds(START, 10), supports, CostModel.DEFAULTS);
            PathSearch search =
                    new PathSearch(live, CostModel.DEFAULTS, goal, stance, new SearchOptions(20_000, 10, 0, 1), policy);
            SearchResult result = search.step(Long.MAX_VALUE, () -> 0L);
            assertEquals(SearchResult.Status.FOUND, result.status(), "trace:\n" + String.join("\n", trace));
            if (result.path().steps().isEmpty()) {
                Pos target = goal.targetAt(stance, live).orElseThrow();
                broken.add(target);
                trace.add("break " + target + " from " + stance);
                continue;
            }
            List<PathStep> steps = result.path().steps();
            for (PathStep step : steps) {
                trace.add(step.move() + " " + step.from() + " -> " + step.to() + " breaks " + step.breaks());
                for (Pos cell : step.breaks()) {
                    assertTrue(
                            stair.contains(cell),
                            "the plan opened " + cell + " beside the stair; trace:\n" + String.join("\n", trace));
                    broken.add(cell);
                }
                rememberSupports(supports, step, stair);
            }
            stance = steps.get(steps.size() - 1).to();
        }
        for (Pos cell : stair) {
            assertTrue(broken.contains(cell), "the stair left " + cell + " solid");
        }
    }

    private static void rememberSupports(Set<Pos> supports, PathStep step, List<Pos> stair) {
        for (Node node : List.of(step.from(), step.to())) {
            if (!stair.contains(node.support())) {
                supports.add(node.support());
            }
        }
    }

    private static CellView live(CellView terrain, Set<Pos> broken) {
        return (x, y, z) -> broken.contains(new Pos(x, y, z)) ? Cell.AIR : terrain.at(x, y, z);
    }

    /** A solid hillside with only the companion's own three-cell pocket open, descending east from the pocket. */
    private static List<Pos> stairCells(int length) {
        List<Pos> cells = new ArrayList<>();
        for (int step = 0; step < length; step++) {
            for (int dy = 0; dy < ExcavationGeometry.MIN_STAIR_HEIGHT; dy++) {
                cells.add(new Pos(START.x() + step, START.y() + dy - step, START.z()));
            }
        }
        return cells;
    }

    private static GridCellView hillside() {
        String[] layers = new String[HEIGHT];
        for (int y = 0; y < HEIGHT; y++) {
            StringBuilder layer = new StringBuilder();
            for (int z = 0; z < DEPTH; z++) {
                if (z > 0) {
                    layer.append('/');
                }
                for (int x = 0; x < WIDTH; x++) {
                    boolean pocket = z == START.z()
                            && x == START.x()
                            && y >= START.y()
                            && y < START.y() + ExcavationGeometry.MIN_STAIR_HEIGHT;
                    layer.append(pocket ? '.' : 'd');
                }
            }
            layers[y] = layer.toString();
        }
        return new GridCellView(layers);
    }
}
