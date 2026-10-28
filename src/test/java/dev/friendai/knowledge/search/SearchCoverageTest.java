package dev.friendai.knowledge.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.features.navigation.pathing.Pos;
import org.junit.jupiter.api.Test;

class SearchCoverageTest {
    @Test
    void surfaceCoverageDoesNotExcludeDeepBand() {
        SearchCoverage coverage = new SearchCoverage();
        coverage.record(
                "minecraft:overworld",
                new Pos(20, 64, 20),
                "minecraft:iron_ore",
                ResourceSearchProfile.Strategy.SURFACE,
                SearchCoverage.Outcome.INSPECTED,
                100);
        assertFalse(coverage.inspected(
                "minecraft:overworld", new Pos(20, 0, 20), "minecraft:iron_ore", ResourceSearchProfile.Strategy.CAVE));
    }

    @Test
    void coverageViewpointsAreBoundedPerSector() {
        SearchCoverage coverage = new SearchCoverage();
        for (int i = 0; i < 12; i++)
            coverage.record(
                    "minecraft:overworld",
                    new Pos(1 + i, 64, 1),
                    "wood",
                    ResourceSearchProfile.Strategy.SURFACE,
                    SearchCoverage.Outcome.INSPECTED,
                    i);
        assertTrue(coverage.snapshot().size() <= 8);
    }

    @Test
    void coverageRoundTripsWithoutInventingViewpoints() {
        SearchCoverage coverage = new SearchCoverage();
        coverage.record(
                "minecraft:overworld",
                new Pos(-17, 8, 31),
                "minecraft:coal",
                ResourceSearchProfile.Strategy.CAVE,
                SearchCoverage.Outcome.INSPECTED,
                99);
        assertEquals(
                coverage.snapshot(), SearchCoverage.fromJson(coverage.toJson()).snapshot());
    }
}
