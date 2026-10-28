package dev.friendai.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.friendai.testutil.GridCellView;
import org.junit.jupiter.api.Test;

class PartialSearchTest {
    @Test
    void sealedRoomReturnsPartialTowardsTheGoal() {
        GridCellView view = new GridCellView("ppppppppp", "p.......p", "p.......p", "ppppppppp");
        SearchResult result = run(view, new Node(1, 1, 0), new GoalPoint(20.5, 1, 0.5, 0.5, 0.5), 64, 0);
        assertEquals(SearchResult.Status.PARTIAL, result.status());
        assertEquals(7, result.path().end().x());
    }

    @Test
    void sealedPocketWithoutImprovementIsNoPath() {
        GridCellView view = new GridCellView("ppp/ppp/ppp", "ppp/p.p/ppp", "ppp/p.p/ppp", "ppp/ppp/ppp");
        SearchResult result = run(view, new Node(1, 1, 1), new GoalPoint(5.5, 1, 0.5, 0.5, 0.5), 64, 0);
        assertEquals(SearchResult.Status.NO_PATH, result.status());
        assertNull(result.path());
    }

    @Test
    void goalBeyondMaxRangeReturnsPartial() {
        GridCellView view = new GridCellView("##########", "..........", "..........");
        SearchResult result = new PathSearch(
                        view,
                        CostModel.DEFAULTS,
                        new GoalPoint(9.5, 1, 0.5, 0.5, 0.5),
                        new Node(0, 1, 0),
                        new SearchOptions(20_000, 3, 0, 1))
                .step(Long.MAX_VALUE, System::nanoTime);
        assertEquals(SearchResult.Status.PARTIAL, result.status());
        assertEquals(3, result.path().end().x());
    }

    private static SearchResult run(GridCellView view, Node start, PathGoal goal, int range, int placeable) {
        return new PathSearch(view, CostModel.DEFAULTS, goal, start, new SearchOptions(20_000, range, placeable, 1))
                .step(Long.MAX_VALUE, System::nanoTime);
    }
}
