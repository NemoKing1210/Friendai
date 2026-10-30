package dev.friendai.features.navigation.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.testutil.GridCellView;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PathTest {

    private static PathStep step(Move move, int fromY, int toY, List<Pos> places) {
        return new PathStep(new Node(0, fromY, 0), new Node(0, toY, 0), move, List.of(), places, 1, 0);
    }

    private static Path complete(List<PathStep> steps) {
        double cost = 0;
        int breaks = 0;
        int places = 0;
        for (PathStep step : steps) {
            cost += step.cost();
            breaks += step.breaks().size();
            places += step.places().size();
        }
        return new Path(steps, cost, breaks, places, false);
    }

    private static Path partial(List<PathStep> steps) {
        Path whole = complete(steps);
        return new Path(whole.steps(), whole.cost(), whole.breaks(), whole.places(), true);
    }

    @Test
    void completeRouteIsItsOwnPassage() {
        Path route =
                complete(List.of(step(Move.WALK, 1, 1, List.of()), step(Move.PILLAR, 1, 2, List.of(new Pos(0, 1, 0)))));

        Optional<Path> passage = route.passagePrefix();

        assertTrue(passage.isPresent(), "a complete route must be walkable whole");
        assertEquals(route, passage.get());
    }

    @Test
    void partialRouteWithoutPlacementsKeepsEveryStep() {
        Path route = partial(List.of(step(Move.WALK, 1, 1, List.of()), step(Move.DIG, 1, 1, List.of())));

        Optional<Path> passage = route.passagePrefix();

        assertTrue(passage.isPresent(), "digging progress changes no block that a later step relies on");
        assertEquals(2, passage.get().steps().size());
        assertTrue(passage.get().partial(), "the caller still has to plan the rest");
    }

    @Test
    void partialRouteStopsBeforeItsFirstPlacement() {
        Path route = partial(List.of(
                step(Move.WALK, 1, 1, List.of()),
                step(Move.PILLAR, 1, 2, List.of(new Pos(0, 1, 0))),
                step(Move.WALK, 2, 2, List.of())));

        Optional<Path> passage = route.passagePrefix();

        assertTrue(passage.isPresent(), "the walk before the first rise is still worth walking");
        assertEquals(1, passage.get().steps().size(), "the half stair must not be walked");
        assertEquals(0, passage.get().places());
        assertTrue(passage.get().partial());
    }

    @Test
    void partialRouteThatPlacesFirstIsRefused() {
        Path route =
                partial(List.of(step(Move.PILLAR, 1, 2, List.of(new Pos(0, 1, 0))), step(Move.WALK, 2, 2, List.of())));

        assertFalse(
                route.passagePrefix().isPresent(),
                "a passage whose very first step builds a stump must be refused, not walked");
    }

    @Test
    void searchRefusesToHandOutAPartialStair() {
        // An unbreakable three-block wall with material for one rise: the search stops short of the goal after a
        // single pillar. That route is the report "one block placed, the way on still too high".
        GridCellView view =
                new GridCellView("##########", "....pppppp", "....pppppp", "....pppppp", "..........", "..........");
        SearchResult result = new PathSearch(
                        view,
                        CostModel.DEFAULTS,
                        new GoalPoint(7.5, 4, 0.5, 1.5, 0.5),
                        new Node(1, 1, 0),
                        new SearchOptions(20_000, 64, 1, 1))
                .step(Long.MAX_VALUE, System::nanoTime);

        assertEquals(SearchResult.Status.PARTIAL, result.status());
        assertEquals(1, result.path().places(), "the search still reports the stump it stopped at");
        Optional<Path> passage = result.path().passagePrefix();
        assertEquals(
                result.path().steps().size() - 1,
                passage.map(path -> path.steps().size()).orElse(0),
                "only the walking before the stump may be walked");
        assertEquals(0, passage.orElseThrow().places());
    }
}
