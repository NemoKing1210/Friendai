package dev.friendai.knowledge.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.pathing.Pos;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SearchPlannerTest {
    private static ResourceSearchProfile profile(Set<ResourceSearchProfile.Strategy> strategies) {
        return new ResourceSearchProfile("minecraft:iron_ore", Set.of("minecraft:overworld"),
                OptionalInt.of(16), strategies);
    }

    @Test
    void newFrontierBeatsRecentlyInspected() {
        var coverage = new SearchCoverage();
        coverage.record("minecraft:overworld", new Pos(16, 64, 0), "minecraft:iron_ore",
                ResourceSearchProfile.Strategy.SURFACE, SearchCoverage.Outcome.INSPECTED, 100);
        var old = new SearchPlanner.Frontier(new Pos(16, 64, 0), new Pos(16, 64, 0),
                ResourceSearchProfile.Strategy.SURFACE, 10, 1);
        var fresh = new SearchPlanner.Frontier(new Pos(32, 64, 0), new Pos(32, 64, 0),
                ResourceSearchProfile.Strategy.SURFACE, 3, 20);
        var request = new SearchPlanner.Request("minecraft:overworld", new Pos(0, 64, 0), List.of(old, fresh),
                profile(Set.of(ResourceSearchProfile.Strategy.SURFACE)), coverage, 100, 200, SearchPlanner.Heading.EAST);
        assertEquals(fresh, new SearchPlanner().choose(request).orElseThrow());
    }

    @Test
    void blockedFrontierRetriesAfterCooldown() {
        var coverage = new SearchCoverage();
        Pos point = new Pos(16, 64, 0);
        coverage.record("minecraft:overworld", point, "minecraft:iron_ore", ResourceSearchProfile.Strategy.SURFACE,
                SearchCoverage.Outcome.BLOCKED, 100);
        var frontier = new SearchPlanner.Frontier(point, point, ResourceSearchProfile.Strategy.SURFACE, 4, 10);
        SearchPlanner planner = new SearchPlanner();
        assertTrue(planner.choose(new SearchPlanner.Request("minecraft:overworld", new Pos(0, 64, 0), List.of(frontier),
                profile(Set.of(ResourceSearchProfile.Strategy.SURFACE)), coverage, 100, 200, SearchPlanner.Heading.EAST)).isEmpty());
        assertEquals(frontier, planner.choose(new SearchPlanner.Request("minecraft:overworld", new Pos(0, 64, 0), List.of(frontier),
                profile(Set.of(ResourceSearchProfile.Strategy.SURFACE)), coverage, 100, 6_101,
                SearchPlanner.Heading.EAST)).orElseThrow());
    }

    @Test
    void unsupportedStrategyHasNoFrontier() {
        var frontier = new SearchPlanner.Frontier(new Pos(10, 20, 0), new Pos(10, 20, 0),
                ResourceSearchProfile.Strategy.MINE, 9, 2);
        assertTrue(new SearchPlanner().choose(new SearchPlanner.Request("minecraft:overworld", new Pos(0, 20, 0),
                List.of(frontier), profile(Set.of(ResourceSearchProfile.Strategy.CAVE)), new SearchCoverage(), 100, 1,
                SearchPlanner.Heading.EAST)).isEmpty());
    }

    @Test
    void tiesPreferCurrentHeadingThenCardinalOrder() {
        var north = new SearchPlanner.Frontier(new Pos(0, 64, -16), new Pos(0, 64, -16), ResourceSearchProfile.Strategy.SURFACE, 2, 20);
        var east = new SearchPlanner.Frontier(new Pos(16, 64, 0), new Pos(16, 64, 0), ResourceSearchProfile.Strategy.SURFACE, 2, 20);
        var south = new SearchPlanner.Frontier(new Pos(0, 64, 16), new Pos(0, 64, 16), ResourceSearchProfile.Strategy.SURFACE, 2, 20);
        var west = new SearchPlanner.Frontier(new Pos(-16, 64, 0), new Pos(-16, 64, 0), ResourceSearchProfile.Strategy.SURFACE, 2, 20);
        var request = new SearchPlanner.Request("minecraft:overworld", new Pos(0, 64, 0), List.of(north, east, south, west),
                profile(Set.of(ResourceSearchProfile.Strategy.SURFACE)), new SearchCoverage(), 100, 0, SearchPlanner.Heading.EAST);
        assertEquals(east, new SearchPlanner().choose(request).orElseThrow());
    }
}
