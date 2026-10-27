package dev.friendai.place;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.pathing.Pos;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlaceAutoRegistrationTest {
    private static final String OVERWORLD = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";
    private static final UUID OWNER = UUID.fromString("6a2f0e54-9c5b-4f4c-9b1e-2d7c7f6a1b3c");

    @Test
    void repeatedFarmPassesUpdateOnePlace() {
        PlaceRegistry registry = new PlaceRegistry(PlaceLimits.DEFAULTS);
        PlaceCandidate wheat = candidate("wheat", "", OVERWORLD, new Pos(10, 64, 10));
        PlaceAutoRegistration.Outcome first = PlaceAutoRegistration.decide(registry, wheat, 16, 100);
        assertEquals(PlaceAutoRegistration.Decision.CREATE, first.decision());
        assertEquals(0, first.targetId());
        Place stored = apply(registry, wheat, first, 100);
        assertEquals("wheat farm", stored.name());
        assertEquals(OWNER, stored.author());

        PlaceAutoRegistration.Outcome again = PlaceAutoRegistration.decide(registry, wheat, 16, 200);
        assertEquals(PlaceAutoRegistration.Decision.UPDATE, again.decision());
        assertEquals(stored.id(), again.targetId());
        assertEquals("wheat farm", again.name());
        assertEquals(1, registry.all().size());
        assertEquals(100, registry.byId(stored.id()).orElseThrow().updatedTick());
    }

    @Test
    void decideWritesNothing() {
        PlaceRegistry registry = new PlaceRegistry(PlaceLimits.DEFAULTS);
        PlaceCandidate wheat = candidate("wheat", "", OVERWORLD, new Pos(10, 64, 10));
        assertEquals(
                PlaceAutoRegistration.Decision.CREATE,
                PlaceAutoRegistration.decide(registry, wheat, 16, 100).decision());
        assertTrue(registry.all().isEmpty());
        assertEquals(0L, registry.revision());
    }

    @Test
    void theCallerSuppliesTheAuthor() {
        PlaceRegistry registry = new PlaceRegistry(PlaceLimits.DEFAULTS);
        PlaceCandidate wheat = candidate("wheat", "", OVERWORLD, new Pos(10, 64, 10));
        PlaceAutoRegistration.Outcome outcome = PlaceAutoRegistration.decide(registry, wheat, 16, 100);
        Place stored = apply(registry, wheat, outcome, 100);
        assertEquals(OWNER, stored.author());
        assertEquals(PlaceSource.FARM, stored.source());
    }

    @Test
    void createUsesTheLabelForTheName() {
        PlaceRegistry registry = new PlaceRegistry(PlaceLimits.DEFAULTS);
        PlaceAutoRegistration.Outcome outcome = PlaceAutoRegistration.decide(
                registry, candidate("wheat", "", OVERWORLD, new Pos(10, 64, 10)), 16, 100);
        assertEquals(PlaceAutoRegistration.Decision.CREATE, outcome.decision());
        assertEquals("wheat farm", outcome.name());
        assertEquals(0, outcome.targetId());
    }

    @Test
    void theMergeRadiusIsChebyshevAndInclusive() {
        PlaceRegistry registry = new PlaceRegistry(PlaceLimits.DEFAULTS);
        PlaceCandidate near = candidate("wheat", "", OVERWORLD, new Pos(0, 64, 0));
        Place first = apply(registry, near, PlaceAutoRegistration.decide(registry, near, 16, 100), 100);

        PlaceCandidate edge = candidate("wheat", "", OVERWORLD, new Pos(16, 64, 16));
        PlaceAutoRegistration.Outcome outcome =
                PlaceAutoRegistration.decide(registry, edge, 16, 200);
        assertEquals(PlaceAutoRegistration.Decision.UPDATE, outcome.decision());
        assertEquals(first.id(), outcome.targetId());
        assertEquals(new Pos(0, 64, 0), registry.byId(first.id()).orElseThrow().anchor());

        Place updated = apply(registry, edge, outcome, 200);
        assertEquals(new Pos(16, 64, 16), updated.anchor());
        assertEquals(200, updated.updatedTick());
        assertEquals(1, registry.all().size());
    }

    @Test
    void outsideTheMergeRadiusCreatesASecondPlace() {
        PlaceRegistry registry = new PlaceRegistry(PlaceLimits.DEFAULTS);
        PlaceCandidate near = candidate("wheat", "", OVERWORLD, new Pos(0, 64, 0));
        Place first = apply(registry, near, PlaceAutoRegistration.decide(registry, near, 16, 100), 100);

        PlaceCandidate far = candidate("wheat", "", OVERWORLD, new Pos(17, 64, 0));
        PlaceAutoRegistration.Outcome outcome =
                PlaceAutoRegistration.decide(registry, far, 16, 200);
        assertEquals(PlaceAutoRegistration.Decision.CREATE, outcome.decision());
        assertEquals("wheat farm 2", outcome.name());
        assertEquals(1, registry.all().size());
        Place second = apply(registry, far, outcome, 200);
        assertEquals(2, registry.all().size());
        assertNotEquals(first.id(), second.id());
    }

    @Test
    void aDifferentLabelCreatesANewPlace() {
        PlaceRegistry registry = new PlaceRegistry(PlaceLimits.DEFAULTS);
        PlaceCandidate wheat = candidate("wheat", "", OVERWORLD, new Pos(0, 64, 0));
        apply(registry, wheat, PlaceAutoRegistration.decide(registry, wheat, 16, 100), 100);

        PlaceCandidate barley = candidate("barley", "", OVERWORLD, new Pos(0, 64, 0));
        PlaceAutoRegistration.Outcome outcome =
                PlaceAutoRegistration.decide(registry, barley, 16, 200);
        assertEquals(PlaceAutoRegistration.Decision.CREATE, outcome.decision());
        assertEquals("barley farm", outcome.name());
        apply(registry, barley, outcome, 200);
        assertEquals(2, registry.all().size());
    }

    @Test
    void aDifferentKindCreatesANewPlace() {
        PlaceRegistry registry = new PlaceRegistry(PlaceLimits.DEFAULTS);
        PlaceCandidate farm = candidate("wheat", "", OVERWORLD, new Pos(0, 64, 0));
        apply(registry, farm, PlaceAutoRegistration.decide(registry, farm, 16, 100), 100);

        PlaceCandidate mine = new PlaceCandidate(
                PlaceKind.MINE, "wheat", "", OVERWORLD, new Pos(0, 64, 0), 6, PlaceSource.MINE);
        PlaceAutoRegistration.Outcome outcome = PlaceAutoRegistration.decide(registry, mine, 16, 200);
        assertEquals(PlaceAutoRegistration.Decision.CREATE, outcome.decision());
        assertEquals("mine", outcome.name());
        apply(registry, mine, outcome, 200);
        assertEquals(2, registry.all().size());
    }

    @Test
    void theSamePlaceInAnotherDimensionIsANewPlace() {
        PlaceRegistry registry = new PlaceRegistry(PlaceLimits.DEFAULTS);
        PlaceCandidate overworld = candidate("wheat", "", OVERWORLD, new Pos(0, 64, 0));
        apply(registry, overworld, PlaceAutoRegistration.decide(registry, overworld, 16, 100), 100);

        PlaceCandidate nether = candidate("wheat", "", NETHER, new Pos(0, 64, 0));
        PlaceAutoRegistration.Outcome outcome = PlaceAutoRegistration.decide(registry, nether, 16, 200);
        assertEquals(PlaceAutoRegistration.Decision.CREATE, outcome.decision());
        assertEquals("wheat farm", outcome.name());
        apply(registry, nether, outcome, 200);
        assertEquals(2, registry.all().size());
    }

    @Test
    void aFreePreferredNameWins() {
        PlaceRegistry registry = new PlaceRegistry(PlaceLimits.DEFAULTS);
        PlaceCandidate gate = candidate("mixed", "North gate", OVERWORLD, new Pos(0, 64, 0));
        PlaceAutoRegistration.Outcome outcome = PlaceAutoRegistration.decide(registry, gate, 16, 100);
        assertEquals(PlaceAutoRegistration.Decision.CREATE, outcome.decision());
        assertEquals("North gate", outcome.name());
        assertEquals("North gate", apply(registry, gate, outcome, 100).name());
    }

    @Test
    void aTakenPreferredNameFallsBackToAnAutoName() {
        PlaceRegistry registry = new PlaceRegistry(PlaceLimits.DEFAULTS);
        registry.remember(values("wheat farm", PlaceKind.FARM, new Pos(30, 64, 30)), 1);
        PlaceCandidate wheat = candidate("wheat", "wheat farm", OVERWORLD, new Pos(0, 64, 0));
        PlaceAutoRegistration.Outcome outcome = PlaceAutoRegistration.decide(registry, wheat, 16, 100);
        assertEquals(PlaceAutoRegistration.Decision.CREATE, outcome.decision());
        assertEquals("wheat farm 2", outcome.name());
    }

    @Test
    void skipWhenTheNameSpaceIsExhausted() {
        PlaceRegistry registry = new PlaceRegistry(PlaceLimits.DEFAULTS);
        registry.remember(values("wheat farm", PlaceKind.FARM, new Pos(0, 64, 0)), 1);
        for (int suffix = 2; suffix <= 999; suffix++) {
            registry.remember(values("wheat farm " + suffix, PlaceKind.FARM, new Pos(0, 64, 0)), 1);
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
        PlaceCandidate wheat = candidate("wheat", "", OVERWORLD, new Pos(0, 64, 0));
        apply(registry, wheat, PlaceAutoRegistration.decide(registry, wheat, 16, 100), 100);
        PlaceAutoRegistration.Outcome outcome = PlaceAutoRegistration.decide(registry, wheat, -1, 200);
        assertEquals(PlaceAutoRegistration.Decision.CREATE, outcome.decision());
        assertEquals("wheat farm 2", outcome.name());
    }

    private static Place apply(
            PlaceRegistry registry,
            PlaceCandidate candidate,
            PlaceAutoRegistration.Outcome outcome,
            long tick) {
        return registry.remember(
                new PlaceValues(
                        outcome.name(), candidate.kind(), candidate.label(), candidate.dimension(),
                        candidate.anchor(), candidate.radius(), Set.of(), candidate.source(), OWNER, ""),
                tick);
    }

    private static PlaceValues values(String name, PlaceKind kind, Pos pos) {
        return new PlaceValues(
                name, kind, "", OVERWORLD, pos, 8, Set.of(), PlaceSource.MANUAL, OWNER, "");
    }

    private static PlaceCandidate candidate(String label, String preferredName, String dimension, Pos anchor) {
        return new PlaceCandidate(PlaceKind.FARM, label, preferredName, dimension, anchor, 6, PlaceSource.FARM);
    }
}
