package dev.friendai.knowledge.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.features.navigation.pathing.Pos;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SearchPlannerTest {
    private static ResourceSearchProfile profile(Set<ResourceSearchProfile.Strategy> strategies) {
        return new ResourceSearchProfile(
                "minecraft:iron_ore", Set.of("minecraft:overworld"), OptionalInt.of(16), strategies);
    }

    @Test
    void newFrontierBeatsRecentlyInspected() {
        var coverage = new SearchCoverage();
        coverage.record(
                "minecraft:overworld",
                new Pos(16, 64, 0),
                "minecraft:iron_ore",
                ResourceSearchProfile.Strategy.SURFACE,
                SearchCoverage.Outcome.INSPECTED,
                100);
        var old = new SearchPlanner.Frontier(
                new Pos(16, 64, 0), new Pos(16, 64, 0), ResourceSearchProfile.Strategy.SURFACE, 10, 1);
        var fresh = new SearchPlanner.Frontier(
                new Pos(32, 64, 0), new Pos(32, 64, 0), ResourceSearchProfile.Strategy.SURFACE, 3, 20);
        var request = new SearchPlanner.Request(
                "minecraft:overworld",
                new Pos(0, 64, 0),
                List.of(old, fresh),
                profile(Set.of(ResourceSearchProfile.Strategy.SURFACE)),
                coverage,
                100,
                200,
                SearchPlanner.Heading.EAST);
        assertEquals(fresh, new SearchPlanner().choose(request).orElseThrow());
    }

    @Test
    void blockedFrontierRetriesAfterCooldown() {
        var coverage = new SearchCoverage();
        Pos point = new Pos(16, 64, 0);
        coverage.record(
                "minecraft:overworld",
                point,
                "minecraft:iron_ore",
                ResourceSearchProfile.Strategy.SURFACE,
                SearchCoverage.Outcome.BLOCKED,
                100);
        var frontier = new SearchPlanner.Frontier(point, point, ResourceSearchProfile.Strategy.SURFACE, 4, 10);
        SearchPlanner planner = new SearchPlanner();
        assertTrue(planner.choose(new SearchPlanner.Request(
                        "minecraft:overworld",
                        new Pos(0, 64, 0),
                        List.of(frontier),
                        profile(Set.of(ResourceSearchProfile.Strategy.SURFACE)),
                        coverage,
                        100,
                        200,
                        SearchPlanner.Heading.EAST))
                .isEmpty());
        assertEquals(
                frontier,
                planner.choose(new SearchPlanner.Request(
                                "minecraft:overworld",
                                new Pos(0, 64, 0),
                                List.of(frontier),
                                profile(Set.of(ResourceSearchProfile.Strategy.SURFACE)),
                                coverage,
                                100,
                                6_101,
                                SearchPlanner.Heading.EAST))
                        .orElseThrow());
    }

    @Test
    void unsupportedStrategyHasNoFrontier() {
        var frontier = new SearchPlanner.Frontier(
                new Pos(10, 20, 0), new Pos(10, 20, 0), ResourceSearchProfile.Strategy.MINE, 9, 2);
        assertTrue(new SearchPlanner()
                .choose(new SearchPlanner.Request(
                        "minecraft:overworld",
                        new Pos(0, 20, 0),
                        List.of(frontier),
                        profile(Set.of(ResourceSearchProfile.Strategy.CAVE)),
                        new SearchCoverage(),
                        100,
                        1,
                        SearchPlanner.Heading.EAST))
                .isEmpty());
    }

    @Test
    void tiesPreferCurrentHeadingThenCardinalOrder() {
        var north = new SearchPlanner.Frontier(
                new Pos(0, 64, -16), new Pos(0, 64, -16), ResourceSearchProfile.Strategy.SURFACE, 2, 20);
        var east = new SearchPlanner.Frontier(
                new Pos(16, 64, 0), new Pos(16, 64, 0), ResourceSearchProfile.Strategy.SURFACE, 2, 20);
        var south = new SearchPlanner.Frontier(
                new Pos(0, 64, 16), new Pos(0, 64, 16), ResourceSearchProfile.Strategy.SURFACE, 2, 20);
        var west = new SearchPlanner.Frontier(
                new Pos(-16, 64, 0), new Pos(-16, 64, 0), ResourceSearchProfile.Strategy.SURFACE, 2, 20);
        var request = new SearchPlanner.Request(
                "minecraft:overworld",
                new Pos(0, 64, 0),
                List.of(north, east, south, west),
                profile(Set.of(ResourceSearchProfile.Strategy.SURFACE)),
                new SearchCoverage(),
                100,
                0,
                SearchPlanner.Heading.EAST);
        assertEquals(east, new SearchPlanner().choose(request).orElseThrow());
    }

    private static SearchPlanner.Frontier land(int x) {
        return new SearchPlanner.Frontier(
                new Pos(x, 64, 0), new Pos(x, 64, 0), ResourceSearchProfile.Strategy.SURFACE, 1, 20);
    }

    private static SearchPlanner.Frontier withTerrain(int x, int viewpoints, SearchPlanner.Terrain terrain) {
        return new SearchPlanner.Frontier(
                new Pos(x, 64, 0), new Pos(x, 64, 0), ResourceSearchProfile.Strategy.SURFACE, viewpoints, 20, terrain);
    }

    private static SearchPlanner.Request request(List<SearchPlanner.Frontier> frontiers, SearchHint hint, long tick) {
        return new SearchPlanner.Request(
                "minecraft:overworld",
                new Pos(0, 64, 0),
                frontiers,
                profile(Set.of(ResourceSearchProfile.Strategy.SURFACE)),
                new SearchCoverage(),
                1_000,
                tick,
                SearchPlanner.Heading.NORTH,
                java.util.Optional.ofNullable(hint));
    }

    @Test
    void waterLosesToEqualCostLand() {
        var east = land(16);
        var west = new SearchPlanner.Frontier(
                new Pos(-16, 64, 0),
                new Pos(-16, 64, 0),
                ResourceSearchProfile.Strategy.SURFACE,
                1,
                20,
                new SearchPlanner.Terrain(false, 1.0, 0, false, ""));
        assertEquals(
                east,
                new SearchPlanner()
                        .choose(request(List.of(east, west), null, 0))
                        .orElseThrow());
    }

    @Test
    void biomeEdgeBeatsFeaturelessGround() {
        var edge = withTerrain(16, 1, new SearchPlanner.Terrain(true, 0, 0, false, "minecraft:forest"));
        assertEquals(
                edge,
                new SearchPlanner()
                        .choose(request(List.of(edge, land(-16)), null, 0))
                        .orElseThrow());
    }

    @Test
    void knownTrailGetsTheDiscount() {
        var trail = withTerrain(16, 1, new SearchPlanner.Terrain(false, 0, 0, true, ""));
        assertEquals(
                trail,
                new SearchPlanner()
                        .choose(request(List.of(trail, land(-16)), null, 0))
                        .orElseThrow());
    }

    @Test
    void returnCostKeepsAFarLegFromWinningOnViewpointsAlone() {
        var far = withTerrain(128, 2, SearchPlanner.Terrain.NEUTRAL);
        var near = withTerrain(16, 1, SearchPlanner.Terrain.NEUTRAL);
        assertEquals(
                near,
                new SearchPlanner().choose(request(List.of(far, near), null, 0)).orElseThrow());
    }

    @Test
    void hintBonusTurnsASlightlyWorseLandFrontierIntoTheChoice() {
        var east = withTerrain(16, 0, SearchPlanner.Terrain.NEUTRAL);
        var west = withTerrain(-16, 1, new SearchPlanner.Terrain(false, 0.2, 0, false, ""));
        assertEquals(
                west,
                new SearchPlanner()
                        .choose(request(List.of(east, west), null, 0))
                        .orElseThrow());

        var hint = SearchHint.create(SearchPlanner.Heading.EAST, null, null, null, 0, "the owner saw forest east", 0);
        assertEquals(
                east,
                new SearchPlanner()
                        .choose(request(List.of(east, west), hint, 0))
                        .orElseThrow());
    }

    @Test
    void expiredHintNoLongerSteers() {
        var east = withTerrain(16, 0, SearchPlanner.Terrain.NEUTRAL);
        var west = withTerrain(-16, 1, new SearchPlanner.Terrain(false, 0.2, 0, false, ""));
        var hint = SearchHint.create(SearchPlanner.Heading.EAST, null, null, null, 0, "stale", 0);
        assertEquals(
                west,
                new SearchPlanner()
                        .choose(request(List.of(east, west), hint, hint.expiresTick()))
                        .orElseThrow());
    }

    @Test
    void rankReportsEverySurvivingAlternativeInOrder() {
        var list = List.of(
                land(16),
                land(-16),
                withTerrain(0, 1, SearchPlanner.Terrain.NEUTRAL),
                new SearchPlanner.Frontier(
                        new Pos(0, 64, -32), new Pos(0, 64, -32), ResourceSearchProfile.Strategy.SURFACE, 1, 20));
        var ranked = new SearchPlanner().rank(request(list, null, 0));
        assertEquals(4, ranked.size());
        assertTrue(ranked.get(0).score() >= ranked.get(1).score());
    }
}
