package dev.friendai.knowledge;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.features.navigation.pathing.Pos;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ResourceKnowledgeTextTest {
    @Test
    void summaryIsBoundedAndLabelsHistoricalCounts() {
        ResourceSite site = new ResourceSite(
                1,
                "minecraft:iron_ore",
                "minecraft:overworld",
                Set.of(new Pos(1, 2, 3)),
                new Pos(1, 2, 3),
                new Pos(0, 2, 3),
                0,
                1,
                ResourceSite.Availability.STALE,
                ResourceSite.RouteStatus.UNKNOWN,
                false);
        String summary = ResourceKnowledgeText.summary(List.of(new ResourceQuery.Result(site, 40, 9, 2000)), 240);
        assertTrue(summary.length() <= 240, summary);
        assertTrue(summary.contains("historical, not guaranteed"), summary);
        assertTrue(summary.contains("count 9"), summary);
    }

    @Test
    void emptyKnowledgeDoesNotInventASummary() {
        assertTrue(ResourceKnowledgeText.summary(List.of(), 600).isEmpty());
    }

    @Test
    void approximateSitesAreClearlyLabeled() {
        ResourceSite site = new ResourceSite(
                2,
                "minecraft:coal_ore",
                "minecraft:overworld",
                Set.of(),
                new Pos(4, 5, 6),
                new Pos(3, 5, 6),
                0,
                1,
                ResourceSite.Availability.STALE,
                ResourceSite.RouteStatus.UNKNOWN,
                true);
        assertTrue(ResourceKnowledgeText.summary(List.of(new ResourceQuery.Result(site, 10, 30, 300)), 600)
                .contains("(approximate)"));
    }
}
