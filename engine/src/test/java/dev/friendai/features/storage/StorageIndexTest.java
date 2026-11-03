package dev.friendai.features.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StorageIndexTest {
    private static ContainerRecord record(
            int x, String kind, long seen, int used, int total, Map<String, Integer> counts) {
        return new ContainerRecord(new StorageKey("minecraft:overworld", x, 64, 0), kind, seen, used, total, counts);
    }

    @Test
    void refreshMovesARecordAndEvictionDropsTheOldest() {
        var index = new StorageIndex(new StorageLimits(16, 8, 600));
        for (int x = 0; x < 16; x++) {
            index.observe(record(x, "chest", 10, 1, 27, Map.of("minecraft:stone", 1)));
        }
        index.observe(record(0, "chest", 20, 1, 27, Map.of("minecraft:stone", 2)));
        index.observe(record(16, "chest", 30, 1, 27, Map.of("minecraft:sand", 1)));
        assertEquals(16, index.size());
        assertTrue(index.record(new StorageKey("minecraft:overworld", 1, 64, 0)).isEmpty());
        assertEquals(
                2,
                index.record(new StorageKey("minecraft:overworld", 0, 64, 0))
                        .orElseThrow()
                        .countOf(Set.of("minecraft:stone")));
    }

    @Test
    void findFiltersByCountAndMarksTheStaleBoundary() {
        var index = new StorageIndex(new StorageLimits(16, 8, 600));
        index.observe(record(0, "chest", 400, 1, 27, Map.of("minecraft:iron_ingot", 5)));
        index.observe(record(1, "chest", 300, 1, 27, Map.of("minecraft:iron_ingot", 40)));
        var large = index.find(Set.of("minecraft:iron_ingot"), 10, 1_000);
        assertEquals(1, large.size());
        assertEquals(40, large.getFirst().count());
        assertTrue(large.getFirst().stale());

        var atBoundary = index.find(Set.of("minecraft:iron_ingot"), 5, 1_000);
        assertFalse(atBoundary.stream()
                .filter(c -> c.count() == 5)
                .findFirst()
                .orElseThrow()
                .stale());
        var pastBoundary = index.find(Set.of("minecraft:iron_ingot"), 5, 1_001);
        assertTrue(pastBoundary.stream()
                .filter(c -> c.count() == 5)
                .findFirst()
                .orElseThrow()
                .stale());
    }

    @Test
    void kindsBeyondTheLimitKeepTheHighestCounts() {
        var index = new StorageIndex(new StorageLimits(16, 8, 600));
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (int i = 0; i < 9; i++) {
            counts.put("minecraft:item" + i, i + 1);
        }
        index.observe(record(0, "chest", 1, 9, 27, counts));
        var stored =
                index.record(new StorageKey("minecraft:overworld", 0, 64, 0)).orElseThrow();
        assertEquals(8, stored.counts().size());
        assertEquals("minecraft:item8", stored.counts().keySet().iterator().next());
        assertEquals(0, index.total(Set.of("minecraft:item0")));
    }

    @Test
    void forgetRemovesAndTotalSumsAcrossDimensions() {
        var index = new StorageIndex(new StorageLimits(16, 8, 600));
        index.observe(record(0, "chest", 10, 1, 27, Map.of("minecraft:iron_ingot", 5)));
        index.observe(new ContainerRecord(
                new StorageKey("minecraft:the_nether", 0, 64, 0),
                "chest",
                10,
                1,
                27,
                Map.of("minecraft:iron_ingot", 7)));
        assertEquals(12, index.total(Set.of("minecraft:iron_ingot")));
        long before = index.generation();
        index.forget(new StorageKey("minecraft:overworld", 0, 64, 0));
        assertEquals(1, index.size());
        assertTrue(index.generation() > before);
    }
}
