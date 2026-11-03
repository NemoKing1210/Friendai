package dev.friendai.features.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StorageTargetsTest {
    private static final String OVERWORLD = "minecraft:overworld";

    @Test
    void aFreshFarContainerBeatsAStaleNearOne() {
        var limits = new StorageLimits(16, 8, 600);
        var index = new StorageIndex(limits);
        index.observe(record(1, 0, 0, 399, 1, Map.of("minecraft:iron_ingot", 5)));
        index.observe(record(8, 0, 0, 990, 1, Map.of("minecraft:iron_ingot", 5)));
        var target = StorageTargets.take(
                        index, OVERWORLD, 0, 64, 0, Set.of("minecraft:iron_ingot"), 1, 1_000, limits)
                .orElseThrow();
        assertEquals(8, target.key().x());
    }

    @Test
    void onlySameDimensionIsEligibleAndStaleIsAFallback() {
        var limits = new StorageLimits(16, 8, 600);
        var index = new StorageIndex(limits);
        index.observe(new ContainerRecord(
                new StorageKey("minecraft:the_nether", 0, 64, 0),
                "chest",
                999,
                1,
                27,
                Map.of("minecraft:iron_ingot", 5)));
        index.observe(record(2, 0, 0, 100, 1, Map.of("minecraft:iron_ingot", 5)));
        var target = StorageTargets.take(
                        index, OVERWORLD, 0, 64, 0, Set.of("minecraft:iron_ingot"), 1, 1_000, limits)
                .orElseThrow();
        assertEquals(OVERWORLD, target.key().dimension());
        assertFalse(StorageTargets.take(
                        index, "minecraft:the_end", 0, 64, 0, Set.of("minecraft:iron_ingot"), 1, 1_000, limits)
                .isPresent());
    }

    @Test
    void storePrefersFreeSlotsAndDepositPutsTheItemFirst() {
        var limits = new StorageLimits(16, 8, 600);
        var index = new StorageIndex(limits);
        index.observe(record(1, 0, 0, 900, 27, Map.of("minecraft:stone", 64)));
        index.observe(record(8, 0, 0, 900, 1, Map.of("minecraft:iron_ingot", 5)));
        assertEquals(
                8,
                StorageTargets.store(index, OVERWORLD, 0, 64, 0, 1_000, limits)
                        .orElseThrow()
                        .key()
                        .x());
        assertEquals(
                8,
                StorageTargets.depositOrder(index.records(), OVERWORLD, "minecraft:iron_ingot", 0, 0, 0)
                        .getFirst()
                        .key()
                        .x());
    }

    private static ContainerRecord record(
            int x, int y, int z, long seen, int used, Map<String, Integer> counts) {
        return new ContainerRecord(new StorageKey(OVERWORLD, x, y, z), "chest", seen, used, 27, counts);
    }

    @Test
    void depositOrderPutsTheItemThenFreeSlotsThenUnrecorded() {
        var limits = new StorageLimits(16, 8, 600);
        var index = new StorageIndex(limits);
        index.observe(record(1, 0, 0, 10, 27, Map.of("minecraft:stone", 64)));
        index.observe(record(2, 0, 0, 10, 1, Map.of("minecraft:iron_ingot", 5)));
        index.observe(record(3, 0, 0, 10, 1, Map.of()));
        var ordered = StorageTargets.depositOrder(
                index.records(), OVERWORLD, "minecraft:iron_ingot", 0, 0, 0);
        assertEquals(2, ordered.get(0).key().x());
        assertEquals(3, ordered.get(1).key().x());
        assertEquals(1, ordered.get(2).key().x());
    }
}
