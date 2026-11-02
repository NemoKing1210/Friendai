package dev.friendai.features.knowledge.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StructurePolicyTest {
    @Test
    void everyCataloguedStructureHasADimensionAndStrategy() {
        for (String id : StructureMarkers.ids()) {
            var policy = StructurePolicy.get(id).orElseThrow(() -> new AssertionError("no policy for " + id));
            assertFalse(policy.dimensions().isEmpty(), id);
            assertTrue(
                    policy.strategy() == StructurePolicy.Strategy.SURFACE
                            || policy.strategy() == StructurePolicy.Strategy.UNDERGROUND
                            || policy.strategy() == StructurePolicy.Strategy.NETHER
                            || policy.strategy() == StructurePolicy.Strategy.OCEAN
                            || policy.strategy() == StructurePolicy.Strategy.INDIRECT,
                    id);
        }
    }

    @Test
    void theSevenNamedKindsAreCovered() {
        assertEquals(7, StructurePolicy.requiredIds().size());
        for (String id : StructurePolicy.requiredIds()) {
            assertTrue(StructureMarkers.get(id).isPresent(), "markers for " + id);
            assertTrue(StructurePolicy.get(id).isPresent(), "policy for " + id);
        }
    }

    @Test
    void dimensionsMatchTheStructure() {
        assertEquals(Set.of(StructurePolicy.OVERWORLD), StructurePolicy.dimensions("village"));
        assertEquals(Set.of(StructurePolicy.OVERWORLD), StructurePolicy.dimensions("minecraft:stronghold"));
        assertEquals(Set.of(StructurePolicy.NETHER), StructurePolicy.dimensions("minecraft:nether_fortress"));
        assertEquals(Set.of(StructurePolicy.NETHER), StructurePolicy.dimensions("bastion_remnant"));
        assertEquals(Set.of(StructurePolicy.OVERWORLD), StructurePolicy.dimensions("ancient_city"));
        assertTrue(StructurePolicy.dimensions("minecraft:doom_castle").isEmpty());
        assertTrue(StructurePolicy.dimensions(null).isEmpty());
    }

    @Test
    void anUnknownDimensionNeverMatches() {
        assertTrue(StructurePolicy.eligible("village", StructurePolicy.OVERWORLD));
        assertFalse(StructurePolicy.eligible("village", StructurePolicy.NETHER));
        assertFalse(StructurePolicy.eligible("nether_fortress", StructurePolicy.OVERWORLD));
        assertFalse(StructurePolicy.eligible("village", ""));
        assertFalse(StructurePolicy.eligible("village", null));
        assertFalse(StructurePolicy.eligible("doom_castle", StructurePolicy.OVERWORLD));
    }

    @Test
    void strategySeparatesVerifiedMarkersFromIndirectClues() {
        assertEquals(
                StructurePolicy.Strategy.INDIRECT,
                StructurePolicy.get("stronghold").orElseThrow().strategy());
        assertEquals(
                StructurePolicy.Strategy.UNDERGROUND,
                StructurePolicy.get("ancient_city").orElseThrow().strategy());
        assertEquals(
                StructurePolicy.Strategy.OCEAN,
                StructurePolicy.get("ocean_monument").orElseThrow().strategy());
        assertEquals(
                StructurePolicy.Strategy.NETHER,
                StructurePolicy.get("bastion_remnant").orElseThrow().strategy());
    }

    @Test
    void structureTargetUsesThePolicyDimensions() {
        var village = StructureMarkers.get("village").orElseThrow();
        var target = new TargetKind.Structure("minecraft:village", village.markers(), village.minimum(), 64);
        assertEquals(Set.of(StructurePolicy.OVERWORLD), target.dimensions());
        assertTrue(target.dimensions().contains(StructurePolicy.OVERWORLD));
    }

    @Test
    void normalizeAddsTheDefaultNamespace() {
        assertEquals("minecraft:village", StructurePolicy.normalize("Village"));
        assertEquals("minecraft:village", StructurePolicy.normalize("minecraft:village"));
        assertEquals("", StructurePolicy.normalize("  "));
        assertEquals("", StructurePolicy.normalize(null));
        assertEquals(
                List.of("minecraft:ancient_city", "minecraft:bastion_remnant"),
                StructurePolicy.ids().stream()
                        .filter(id -> id.endsWith("ancient_city") || id.endsWith("bastion_remnant"))
                        .toList());
    }
}
