package dev.friendai.mining;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.pathing.Cell;
import dev.friendai.pathing.CellView;
import dev.friendai.pathing.CostModel;
import dev.friendai.pathing.MoveGenerator;
import dev.friendai.pathing.Node;
import dev.friendai.pathing.Path;
import dev.friendai.pathing.PathSearch;
import dev.friendai.pathing.PathStep;
import dev.friendai.pathing.Pos;
import dev.friendai.pathing.SearchOptions;
import dev.friendai.pathing.SearchResult;
import dev.friendai.testutil.GridCellView;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** A mining staircase must leave room for a two-block-tall body at every node once the earlier steps are dug. */
class MiningStairHeadroomTest {

    @Test
    void ascendingStaircaseToBuriedOreIsWalkableAfterTheDig() {
        GridCellView view = new GridCellView(mountain());
        Pos ore = new Pos(9, 7, 2);
        Node start = new Node(1, 2, 2);
        MiningGoal goal = new MiningGoal(List.of(ore), 1.5, 1.62, Set.of(), Set.of(), true);
        MiningPathPolicy policy = new MiningPathPolicy(view, new MiningBounds(start, 12), Set.of(), CostModel.DEFAULTS);
        PathSearch search = new PathSearch(view, CostModel.DEFAULTS, goal, start,
                new SearchOptions(20_000, 12, 0, 1), policy);

        SearchResult result = search.step(Long.MAX_VALUE, () -> 0L);

        assertEquals(SearchResult.Status.FOUND, result.status(), "expanded=" + search.expanded());
        Path path = result.path();
        Set<Pos> dug = new HashSet<>();
        for (PathStep step : path.steps()) {
            dug.addAll(step.breaks());
            CellView after = (x, y, z) -> dug.contains(new Pos(x, y, z)) ? Cell.AIR : view.at(x, y, z);
            assertTrue(MoveGenerator.standable(after, step.to()),
                    "node " + step.to() + " is not standable after " + step.move() + " breaks " + step.breaks());
            if (step.to().y() > step.from().y()) {
                Pos aboveHead = step.from().pos().offset(0, 2, 0);
                assertTrue(after.at(aboveHead.x(), aboveHead.y(), aboveHead.z()).free(),
                        "no room to jump out of " + step.from() + " for " + step.move());
            }
        }
    }

    @Test
    void descendingStaircaseToBuriedOreIsWalkableAfterTheDig() {
        GridCellView view = new GridCellView(plateau());
        Pos ore = new Pos(7, 4, 2);
        Node start = new Node(2, 11, 2);
        MiningGoal goal = new MiningGoal(List.of(ore), 1.5, 1.62, Set.of(), Set.of(), true);
        MiningPathPolicy policy = new MiningPathPolicy(view, new MiningBounds(start, 12), Set.of(), CostModel.DEFAULTS);
        PathSearch search = new PathSearch(view, CostModel.DEFAULTS, goal, start,
                new SearchOptions(20_000, 12, 0, 1), policy);

        SearchResult result = search.step(Long.MAX_VALUE, () -> 0L);

        assertEquals(SearchResult.Status.FOUND, result.status(), "expanded=" + search.expanded());
        Set<Pos> dug = new HashSet<>();
        StringBuilder trace = new StringBuilder();
        for (PathStep step : result.path().steps()) {
            trace.append(step.move()).append(' ').append(step.from()).append("->").append(step.to())
                    .append(" breaks ").append(step.breaks()).append('\n');
        }
        for (PathStep step : result.path().steps()) {
            dug.addAll(step.breaks());
            CellView after = (x, y, z) -> dug.contains(new Pos(x, y, z)) ? Cell.AIR : view.at(x, y, z);
            assertTrue(MoveGenerator.standable(after, step.to()), trace.toString());
            assertTrue(after.at(step.from().x(), step.from().y() + 2, step.from().z()).free()
                    || step.to().y() < step.from().y(), trace.toString());
        }
    }

    private static String[] plateau() {
        int width = 12;
        int depth = 5;
        String[] layers = new String[14];
        for (int y = 0; y < layers.length; y++) {
            StringBuilder layer = new StringBuilder();
            for (int z = 0; z < depth; z++) {
                if (z > 0) layer.append('/');
                for (int x = 0; x < width; x++) {
                    layer.append(y <= 10 ? 'd' : '.');
                }
            }
            layers[y] = layer.toString();
        }
        return layers;
    }

    private static String[] mountain() {
        int width = 12;
        int depth = 5;
        String[] layers = new String[11];
        for (int y = 0; y < layers.length; y++) {
            StringBuilder layer = new StringBuilder();
            for (int z = 0; z < depth; z++) {
                if (z > 0) layer.append('/');
                for (int x = 0; x < width; x++) {
                    layer.append(y <= 1 || x >= 3 && y <= 9 ? 'd' : '.');
                }
            }
            layers[y] = layer.toString();
        }
        return layers;
    }
}
