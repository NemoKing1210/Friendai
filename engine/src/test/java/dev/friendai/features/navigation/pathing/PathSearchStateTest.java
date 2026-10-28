package dev.friendai.features.navigation.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.friendai.testutil.GridCellView;
import org.junit.jupiter.api.Test;

class PathSearchStateTest {
    @Test
    void aCheaperStateWithMorePlacementsDoesNotHideTheFrugalRoute() {
        // Holes in the floor at (3,0,0) and (5,0,0). Bridging the first at x = 3 is the cheap way to (4,1,0); the
        // frugal way walks around through z = 1 and 2. Only one block may be placed, so the cheap state at (4,1,0)
        // can never bridge the second hole, and the wall at z = 1, x >= 5 stops it walking around. A position-only
        // closed set would keep the cheap state and lose the only route that can finish.
        GridCellView view = new GridCellView(
                "###.#.##/###.####/########", "......../.....ppp/........", "......../.....ppp/........");
        SearchResult result = new PathSearch(
                        view,
                        CostModel.DEFAULTS,
                        new GoalPoint(6.5, 1, 0.5, 0.5, 0.5),
                        new Node(1, 1, 0),
                        new SearchOptions(20_000, 64, 1, 1))
                .step(Long.MAX_VALUE, System::nanoTime);
        assertEquals(SearchResult.Status.FOUND, result.status());
        assertEquals(1, result.path().places());
    }
}
