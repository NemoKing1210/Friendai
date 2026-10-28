package dev.friendai.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.pathing.Pos;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ResourceKnowledgeTest {
    private static ResourceObservation observation(String block, String dimension, Pos pos, long tick) {
        return new ResourceObservation(
                block, new ResourceObservation.Location(dimension, pos), new Pos(0, 10, 0), tick);
    }

    @Test
    void sameBlockObservedTwiceCountsOnce() {
        ResourceKnowledge book = new ResourceKnowledge(KnowledgeLimits.DEFAULTS);
        book.observe(observation("minecraft:coal_ore", "minecraft:overworld", new Pos(1, 10, 1), 1), "coal");
        long id = book.observe(observation("minecraft:coal_ore", "minecraft:overworld", new Pos(1, 10, 1), 2), "coal");
        assertEquals(
                1,
                ResourceQuery.find(
                                book,
                                new ResourceQuery.Request(
                                        java.util.Set.of("minecraft:coal_ore"),
                                        "minecraft:overworld",
                                        new Pos(0, 10, 0),
                                        100,
                                        5,
                                        true))
                        .getFirst()
                        .lastObservedBlocks());
    }

    @Test
    void connectedObservationMergesIds() {
        ResourceKnowledge book = new ResourceKnowledge(KnowledgeLimits.DEFAULTS);
        long first =
                book.observe(observation("minecraft:coal_ore", "minecraft:overworld", new Pos(0, 10, 0), 1), "coal");
        long second = book.observe(
                observation("minecraft:deepslate_coal_ore", "minecraft:overworld", new Pos(2, 10, 0), 2), "coal");
        assertNotEquals(first, second);
        long merged =
                book.observe(observation("minecraft:coal_ore", "minecraft:overworld", new Pos(1, 10, 0), 3), "coal");
        assertEquals(first, merged);
        assertEquals(first, book.site(second, 3).orElseThrow().id());
        assertEquals(
                first,
                ResourceQuery.find(
                                book,
                                new ResourceQuery.Request(
                                        java.util.Set.of("minecraft:deepslate_coal_ore"),
                                        "minecraft:overworld",
                                        new Pos(0, 10, 0),
                                        100,
                                        5,
                                        true))
                        .getFirst()
                        .site()
                        .id());
    }

    @Test
    void coveredIsNotAbsent() {
        ResourceKnowledge book = new ResourceKnowledge(KnowledgeLimits.DEFAULTS);
        long id = book.observe(observation("minecraft:coal_ore", "minecraft:overworld", new Pos(1, 10, 1), 1), "coal");
        assertEquals(1, book.site(id, 30_000).orElseThrow().members().size());
    }

    @Test
    void staleAfter24000Ticks() {
        ResourceKnowledge book = new ResourceKnowledge(KnowledgeLimits.DEFAULTS);
        long id = book.observe(observation("minecraft:coal_ore", "minecraft:overworld", new Pos(1, 10, 1), 1), "coal");
        assertEquals(
                ResourceSite.Availability.STALE,
                book.site(id, 24_001).orElseThrow().availability());
    }

    @Test
    void activeTargetSurvivesCompaction() {
        ResourceKnowledge book = new ResourceKnowledge(new KnowledgeLimits(2, 3, 4, 2, 2));
        long pinned =
                book.observe(observation("minecraft:coal_ore", "minecraft:overworld", new Pos(1, 10, 1), 1), "coal");
        book.pin(pinned);
        book.observe(observation("minecraft:iron_ore", "minecraft:overworld", new Pos(20, 10, 1), 2), "iron");
        book.observe(observation("minecraft:gold_ore", "minecraft:overworld", new Pos(40, 10, 1), 3), "gold");
        assertTrue(book.site(pinned, 3).isPresent());
        assertTrue(book.site(2, 3).orElseThrow().approximate());
    }

    @Test
    void removingOneMemberUpdatesTheBlockIndex() {
        ResourceKnowledge book = new ResourceKnowledge(KnowledgeLimits.DEFAULTS);
        Pos first = new Pos(0, 10, 0);
        Pos second = new Pos(1, 10, 0);
        long id = book.observe(observation("minecraft:coal_ore", "minecraft:overworld", first, 1), "coal");
        book.observe(observation("minecraft:coal_ore", "minecraft:overworld", second, 2), "coal");
        assertEquals(2, book.historicalBlockCount(id));

        book.extracted(new ResourceObservation.Location("minecraft:overworld", first), 3);
        assertEquals(1, book.historicalBlockCount(id));
        var request = new ResourceQuery.Request(
                Set.of("minecraft:coal_ore"), "minecraft:overworld", new Pos(0, 10, 0), 100, 5, true);
        var results = ResourceQuery.find(book, request);
        assertEquals(1, results.size());
        assertEquals(1, results.get(0).lastObservedBlocks());

        book.extracted(new ResourceObservation.Location("minecraft:overworld", second), 4);
        assertEquals(0, book.historicalBlockCount(id));
        assertEquals(
                ResourceSite.Availability.DEPLETED,
                book.site(id, 4).orElseThrow().availability());
        assertTrue(ResourceQuery.find(book, request).isEmpty());
    }
}
