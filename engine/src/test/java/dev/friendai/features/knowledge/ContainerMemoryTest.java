package dev.friendai.features.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.foundation.spatial.Pos;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** What the companion remembers about containers, and that it never silently forgets a new one. */
class ContainerMemoryTest {
    private static ResourceObservation.Location at(int x) {
        return new ResourceObservation.Location("minecraft:overworld", new Pos(x, 64, 0));
    }

    private static ResourceKnowledge.ContainerObservation chest(int x, long tick, Map<String, Integer> items) {
        return new ResourceKnowledge.ContainerObservation(at(x), items, tick, "chest", items.size(), 27);
    }

    @Test
    void aFullContainerBookForgetsTheOldestInsteadOfTheNewest() {
        ResourceKnowledge book = new ResourceKnowledge(KnowledgeLimits.DEFAULTS);
        for (int i = 0; i < ResourceKnowledge.MAX_CONTAINERS; i++) {
            assertTrue(book.recordContainer(chest(i, 100 + i, Map.of("minecraft:dirt", 1))));
        }
        assertTrue(
                book.recordContainer(chest(9_999, 1_000, Map.of("minecraft:ender_pearl", 1))),
                "the chest just opened is the one worth keeping");
        var locations = book.containerObservations().stream()
                .map(ResourceKnowledge.ContainerObservation::location)
                .toList();
        assertEquals(ResourceKnowledge.MAX_CONTAINERS, locations.size());
        assertTrue(locations.contains(at(9_999)));
        assertFalse(locations.contains(at(0)), "the chest seen longest ago made room");
    }

    @Test
    void aFullSightingBookIsCompactedToKeepTheContainer() {
        // Every sighting slot taken: a container record used to fail here, so the companion stopped remembering any
        // chest it opened once it had looked at enough of the world.
        ResourceKnowledge book = new ResourceKnowledge(KnowledgeLimits.DEFAULTS);
        int sightings = ResourceKnowledge.MAX_EXACT_RECORDS;
        for (int i = 0; i < sightings; i++) {
            // One long connected seam, so every sighting stays a member of one site instead of being summarized.
            book.observe(
                    new ResourceObservation(
                            "minecraft:stone",
                            new ResourceObservation.Location("minecraft:overworld", new Pos(i, 10, 500)),
                            new Pos(0, 10, 0),
                            1 + i),
                    "stone");
        }
        assertTrue(book.recordContainer(chest(1, 50_000, Map.of("minecraft:ender_pearl", 1))));
        assertEquals(1, book.containerObservations().size());
    }

    @Test
    void aLiveRefreshFollowsTheContentsWithoutRenewingTheWindow() {
        ResourceKnowledge book = new ResourceKnowledge(KnowledgeLimits.DEFAULTS);
        book.recordContainer(chest(1, 1_000, Map.of("minecraft:dirt", 3)));
        assertTrue(book.refreshContainer(at(1), Map.of("minecraft:dirt", 3, "minecraft:ender_pearl", 1), 2));
        var refreshed = book.containerObservations().getFirst();
        assertEquals(Map.of("minecraft:dirt", 3, "minecraft:ender_pearl", 1), refreshed.items());
        assertEquals(1_000, refreshed.observedTick(), "only opening the container starts a new live window");
        assertFalse(book.refreshContainer(at(2), Map.of(), 0), "nothing to refresh where nothing was opened");
    }

    @Test
    void theLiveWindowIsShorterThanTheMemory() {
        assertTrue(ContainerFreshness.live(0, ContainerFreshness.LIVE_TICKS));
        assertFalse(ContainerFreshness.live(0, ContainerFreshness.LIVE_TICKS + 1));
        assertTrue(ContainerFreshness.remembered(0, ContainerFreshness.LIVE_TICKS + 1));
        assertFalse(ContainerFreshness.remembered(0, ContainerFreshness.REMEMBER_TICKS + 1));
    }
}
