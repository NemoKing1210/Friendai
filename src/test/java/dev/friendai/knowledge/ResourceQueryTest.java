package dev.friendai.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.pathing.Pos;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ResourceQueryTest {
    private static void observe(ResourceKnowledge book, String block, String dim, Pos pos, long tick) {
        book.observe(new ResourceObservation(block, new ResourceObservation.Location(dim, pos), pos, tick), block);
    }

    private static ResourceQuery.Request request(String dim, Pos origin) {
        return new ResourceQuery.Request(Set.of("minecraft:coal_ore"), dim, origin, 100, 5, true);
    }

    @Test
    void queryDistanceChangesWithOrigin() {
        ResourceKnowledge book = new ResourceKnowledge(KnowledgeLimits.DEFAULTS);
        observe(book, "minecraft:coal_ore", "minecraft:overworld", new Pos(3, 10, 4), 1);
        assertEquals(5.0, ResourceQuery.find(book, request("minecraft:overworld", new Pos(0, 10, 0))).getFirst().distanceBlocks());
        assertEquals(10.0, ResourceQuery.find(book, request("minecraft:overworld", new Pos(3, 10, 14))).getFirst().distanceBlocks());
    }

    @Test
    void otherDimensionExcluded() {
        ResourceKnowledge book = new ResourceKnowledge(KnowledgeLimits.DEFAULTS);
        observe(book, "minecraft:coal_ore", "minecraft:the_nether", new Pos(0, 10, 0), 1);
        assertTrue(ResourceQuery.find(book, request("minecraft:overworld", new Pos(0, 10, 0))).isEmpty());
    }
}
