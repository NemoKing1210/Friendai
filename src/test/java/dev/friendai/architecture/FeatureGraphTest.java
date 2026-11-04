package dev.friendai.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.architecture.PackageDependencyRules.DependencyEdge;
import dev.friendai.architecture.PackageDependencyRules.Violation;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FeatureGraphTest {
    @Test
    void reportsATwoFeatureCycle() {
        var edges = List.of(
                edge("dev.friendai.features.mining.A", "dev.friendai.features.navigation.B"),
                edge("dev.friendai.features.navigation.B", "dev.friendai.features.mining.A"));

        assertEquals(List.of(Set.of("mining", "navigation")), FeatureGraph.cycles(edges));
    }

    @Test
    void aBaselineSccAllowsItsSubsetsButNotANewCycle() {
        var baseline = List.of(Set.of("mining", "navigation", "construction"));
        var smaller = List.of(
                edge("dev.friendai.features.mining.A", "dev.friendai.features.navigation.B"),
                edge("dev.friendai.features.navigation.B", "dev.friendai.features.mining.A"));

        assertTrue(FeatureGraph.violations(smaller, baseline).isEmpty());

        var newCycle = List.of(
                edge("dev.friendai.features.memory.A", "dev.friendai.features.social.B"),
                edge("dev.friendai.features.social.B", "dev.friendai.features.memory.A"));

        assertEquals(
                List.of(violation(
                        "feature-graph-acyclic", "dev.friendai.features.memory.A", "dev.friendai.features.social.B")),
                FeatureGraph.violations(newCycle, baseline));
    }

    @Test
    void aSelfEdgeInsideOneFeatureIsNotACycle() {
        var edges = List.of(edge("dev.friendai.features.mining.A", "dev.friendai.features.mining.B"));

        assertTrue(FeatureGraph.cycles(edges).isEmpty());
    }

    @Test
    void classNamesOutsideTheFeatureTreeHaveNoFeatureBoundary() {
        assertNull(FeatureGraph.featureOf("dev.friendai.foundation.spatial.Pos"));
        assertNull(FeatureGraph.featureOf("net.minecraft.world.level.Level"));
        assertEquals("mining", FeatureGraph.featureOf("dev.friendai.features.mining.A"));
    }

    private static DependencyEdge edge(String sourceClass, String targetClass) {
        return new DependencyEdge(sourceClass, targetClass);
    }

    private static Violation violation(String ruleId, String sourceClass, String targetClass) {
        return new Violation(ruleId, sourceClass, targetClass);
    }
}
