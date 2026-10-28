package dev.friendai.features.navigation.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.testutil.GridCellView;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PathPolicyTest {

    private static final Node START = new Node(0, 1, 0);
    private static final GoalPoint GOAL = new GoalPoint(2.5, 1, 0.5, 0.5, 0.5);
    private static final GridCellView VIEW = new GridCellView("########", "........", "........");

    @Test
    void defaultPolicyPreservesRoute() {
        SearchOptions options = new SearchOptions(20_000, 16, 0, 1);
        Path oldPath = new PathSearch(VIEW, CostModel.DEFAULTS, GOAL, START, options)
                .step(Long.MAX_VALUE, System::nanoTime)
                .path();
        Path newPath = new PathSearch(VIEW, CostModel.DEFAULTS, GOAL, START, options, PathPolicy.DEFAULT)
                .step(Long.MAX_VALUE, System::nanoTime)
                .path();

        assertEquals(oldPath.steps(), newPath.steps());
        assertEquals(oldPath.cost(), newPath.cost(), 1e-9);
    }

    @Test
    void rejectedEdgeIsNotRelaxed() {
        GridCellView oneExit = new GridCellView("ppppp/ppppp/ppppp", "ppppp/p...p/ppppp", "ppppp/ppppp/ppppp");
        PathPolicy rejectAll = (view, step) -> Optional.empty();
        SearchResult result = new PathSearch(
                        oneExit,
                        CostModel.DEFAULTS,
                        new GoalPoint(3.5, 1, 1.5, 0.5, 0.5),
                        new Node(1, 1, 1),
                        new SearchOptions(100, 8, 0, 1),
                        rejectAll)
                .step(Long.MAX_VALUE, System::nanoTime);

        assertEquals(SearchResult.Status.NO_PATH, result.status());
        assertTrue(result.path() == null);
    }

    @Test
    void preparedBreaksAndCostReachThePath() {
        Pos clearance = new Pos(1, 1, 0);
        PathPolicy addClearance = (view, step) -> Optional.of(new PathStep(
                step.from(),
                step.to(),
                step.move(),
                step.from().x() == 0 ? List.of(clearance) : step.breaks(),
                step.places(),
                step.cost() + (step.from().x() == 0 ? 4 : 0),
                step.headUnderwater()));
        GoalPoint adjacentGoal = new GoalPoint(1.5, 1, 0.5, 0.5, 0.5);
        Path path = new PathSearch(
                        VIEW, CostModel.DEFAULTS, adjacentGoal, START, new SearchOptions(100, 8, 0, 1), addClearance)
                .step(Long.MAX_VALUE, System::nanoTime)
                .path();

        assertEquals(1, path.steps().getFirst().breaks().size());
        assertTrue(path.steps().getFirst().breaks().contains(clearance));
        assertEquals(CostModel.DEFAULTS.walk() + 4, path.cost(), 1e-9);
    }

    @Test
    void lowerOrNonfiniteCostIsRejected() {
        PathPolicy lowerCost = (view, step) -> Optional.of(withCost(step, step.cost() - 1));
        PathPolicy infiniteCost = (view, step) -> Optional.of(withCost(step, Double.POSITIVE_INFINITY));

        for (PathPolicy policy : List.of(lowerCost, infiniteCost)) {
            SearchResult result = new PathSearch(
                            VIEW, CostModel.DEFAULTS, GOAL, START, new SearchOptions(100, 8, 0, 1), policy)
                    .step(Long.MAX_VALUE, System::nanoTime);
            assertEquals(SearchResult.Status.NO_PATH, result.status());
            assertFalse(result.path() != null);
        }
    }

    private static PathStep withCost(PathStep step, double cost) {
        return new PathStep(
                step.from(), step.to(), step.move(), step.breaks(), step.places(), cost, step.headUnderwater());
    }
}
