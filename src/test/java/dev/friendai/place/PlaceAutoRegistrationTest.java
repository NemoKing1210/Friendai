package dev.friendai.place;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.pathing.Pos;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PlaceAutoRegistrationTest {
    private static final String OVERWORLD = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";

    @Test
    void repeatedFarmPassesUpdateOnePlace() {
        PlaceRegistry registry = new PlaceRegistry(PlaceLimits.DEFAULTS);
        PlaceCandidate wheat = new PlaceCandidate(
                PlaceKind.FARM, "wheat", "", "minecraft:overworld", new Pos(10, 64, 10), 6, PlaceSource.FARM);
        PlaceAutoRegistration.Outcome first = PlaceAutoRegistration.decide(registry, wheat, 16, 100);
        PlaceAutoRegistration.Outcome again = PlaceAutoRegistration.decide(registry, wheat, 16, 200);
        assertEquals(PlaceAutoRegistration.Decision.CREATE, first.decision());
        assertEquals(PlaceAutoRegistration.Decision.UPDATE, again.decision());
        assertEquals(first.targetId(), again.targetId());
        assertEquals(1, registry.all().size());
        assertEquals(200, registry.byId(first.targetId()).orElseThrow().updatedTick());
    }

    @Test
    void createUsesTheLabelAndRemembersThePlace() {
        PlaceRegistry registry = new PlaceRegistry(PlaceLimits.DEFAULTS);
        PlaceAutoRegistration.Outcome outcome = PlaceAutoRegistration.decide(
                registry, candidate("wheat", "", OVERWORLD, new Pos(10, 64, 10)), 16, 100);
        assertEquals(PlaceAutoRegistration.Decision.CREATE, outcome.decision());
        assertEquals("wheat farm", outcome.name());
        Place stored = registry.byId(outcome.targetId()).orElseThrow();
        assertEquals("wheat farm", stored.name());
        assertEquals(PlaceKind.FARM, stored.kind());
        assertEquals(PlaceSource.FARM, stored.source());
    }

    @Test
    void theMergeRadiusIsChebyshevAndInclusive() {
        PlaceRegistry registry = new PlaceRegistry(PlaceLimits.DEFAULTS);
        PlaceAutoRegistration.Outcome first = PlaceAutoRegistration.decide(
                registry, candidate("wheat", "", OVERWORLD, new Pos(0, 64, 0)), 16, 100);
        PlaceAutoRegistration.Outcome edge = PlaceAutoRegistration.decide(
                registry, candidate("wheat", "", OVERWORLD, new Pos(16, 64, 16)), 16, 200);
        assertEquals(PlaceAutoRegistration.Decision.UPDATE, edge.decision());
        assertEquals(first.targetId(), edge.targetId());
        assertEquals(new Pos(16, 64, 16), registry.byId(first.targetId()).orElseThrow().anchor());
    }

    @Test
    void outsideTheMergeRadiusCreatesASecondPlace() {
        PlaceRegistry registry = new PlaceRegistry(PlaceLimits.DEFAULTS);
        PlaceAutoRegistration.Outcome first = PlaceAutoRegistration.decide(
                registry, candidate("wheat", "", OVERWORLD, new Pos(0, 64, 0)), 16, 100);
        PlaceAutoRegistration.Outcome second = PlaceAutoRegistration.decide(
                registry, candidate("wheat", "", OVERWORLD, new Pos(17, 64, 0)), 16, 200);
        assertEquals(PlaceAutoRegistration.Decision.CREATE, second.decision());
        assertEquals("wheat farm 2", second.name());
        assertEquals(2, registry.all().size());
        assertTrue(second.targetId() > first.targetId());
    }

    @Test
    void aDifferentLabelCreatesANewPlace() {
        PlaceRegistry registry = new PlaceRegistry(PlaceLimits.DEFAULTS);
        PlaceAutoRegistration.decide(registry, candidate("wheat", "", OVERWORLD, new Pos(0, 64, 0)), 16, 100);
        PlaceAutoRegistration.Outcome second = PlaceAutoRegistration.decide(
                registry, candidate("barley", "", OVERWORLD, new Pos(0, 64, 0)), 16, 200);
        assertEquals(PlaceAutoRegistration.Decision.CREATE, second.decision());
        assertEquals("barley farm", second.name());
        assertEquals(2, registry.all().size());
    }

    @Test
    void aDifferentKindCreatesANewPlace() {
        PlaceRegistry registry = new PlaceRegistry(PlaceLimits.DEFAULTS);
        PlaceAutoRegistration.decide(registry, candidate("wheat", "", OVERWORLD, new Pos(0, 64, 0)), 16, 100);
        PlaceCandidate mine = new PlaceCandidate(
                PlaceKind.MINE, "wheat", "", OVERWORLD, new Pos(0, 64, 0), 6, PlaceSource.MINE);
        PlaceAutoRegistration.Outcome second = PlaceAutoRegistration.decide(registry, mine, 16, 200);
        assertEquals(PlaceAutoRegistration.Decision.CREATE, second.decision());
        assertEquals("mine", second.name());
        assertEquals(2, registry.all().size());
    }

    @Test
    void theSamePlaceInAnotherDimensionIsANewPlace() {
        PlaceRegistry registry = new PlaceRegistry(PlaceLimits.DEFAULTS);
        PlaceAutoRegistration.decide(registry, candidate("wheat", "", OVERWORLD, new Pos(0, 64, 0)), 16, 100);
        PlaceAutoRegistration.Outcome second = PlaceAutoRegistration.decide(
                registry, candidate("wheat", "", NETHER, new Pos(0, 64, 0)), 16, 200);
        assertEquals(PlaceAutoRegistration.Decision.CREATE, second.decision());
        assertEquals("wheat farm", second.name());
        assertEquals(2, registry.all().size());
    }

    @Test
    void aFreePreferredNameWins() {
        PlaceRegistry registry = new PlaceRegistry(PlaceLimits.DEFAULTS);
        PlaceAutoRegistration.Outcome outcome = PlaceAutoRegistration.decide(
                registry, candidate("mixed", "North gate", OVERWORLD, new Pos(0, 64, 0)), 16, 100);
        assertEquals(PlaceAutoRegistration.Decision.CREATE, outcome.decision());
        assertEquals("North gate", outcome.name());
        assertEquals("North gate", registry.byId(outcome.targetId()).orElseThrow().name());
    }

    @Test
    void aTakenPreferredNameFallsBackToAnAutoName() {
        PlaceRegistry registry = new PlaceRegistry(PlaceLimits.DEFAULTS);
        registry.remember(
                new PlaceValues(
                        "wheat farm", PlaceKind.FARM, "", OVERWORLD, new Pos(30, 64, 30), 8, Set.of(),
                        PlaceSource.MANUAL, null, ""),
                1);
        PlaceAutoRegistration.Outcome outcome = PlaceAutoRegistration.decide(
                registry, candidate("wheat", "wheat farm", OVERWORLD, new Pos(0, 64, 0)), 16, 100);
        assertEquals(PlaceAutoRegistration.Decision.CREATE, outcome.decision());
        assertEquals("wheat farm 2", outcome.name());
    }

    @Test
    void skipWhenTheNameSpaceIsExhausted() {
        PlaceRegistry registry = new PlaceRegistry(PlaceLimits.DEFAULTS);
        registry.remember(
                new PlaceValues(
                        "wheat farm", PlaceKind.FARM, "", OVERWORLD, new Pos(0, 64, 0), 8, Set.of(),
                        PlaceSource.MANUAL, null, ""),
                1);
        for (int suffix = 2; suffix <= 999; suffix++) {
            registry.remember(
                    new PlaceValues(
                            "wheat farm " + suffix, PlaceKind.FARM, "", OVERWORLD, new Pos(0, 64, 0), 8,
                            Set.of(), PlaceSource.MANUAL, null, ""),
                    1);
        }
        int before = registry.all().size();
        PlaceAutoRegistration.Outcome outcome = PlaceAutoRegistration.decide(
                registry, candidate("wheat", "", OVERWORLD, new Pos(0, 64, 0)), 16, 100);
        assertEquals(PlaceAutoRegistration.Decision.SKIP, outcome.decision());
        assertEquals(0, outcome.targetId());
        assertEquals("", outcome.name());
        assertEquals(before, registry.all().size());
    }

    @Test
    void aNegativeMergeRadiusNeverMerges() {
        PlaceRegistry registry = new PlaceRegistry(PlaceLimits.DEFAULTS);
        PlaceAutoRegistration.decide(registry, candidate("wheat", "", OVERWORLD, new Pos(0, 64, 0)), 16, 100);
        PlaceAutoRegistration.Outcome second = PlaceAutoRegistration.decide(
                registry, candidate("wheat", "", OVERWORLD, new Pos(0, 64, 0)), -1, 200);
        assertEquals(PlaceAutoRegistration.Decision.CREATE, second.decision());
    }

    private static PlaceCandidate candidate(String label, String preferredName, String dimension, Pos anchor) {
        return new PlaceCandidate(PlaceKind.FARM, label, preferredName, dimension, anchor, 6, PlaceSource.FARM);
    }
}
