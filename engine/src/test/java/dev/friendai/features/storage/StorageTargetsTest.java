package dev.friendai.features.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StorageTargetsTest {
    private static final String OVERWORLD = "minecraft:overworld";
    private static final Set<String> ANDESITE = Set.of("minecraft:andesite");

    @Test
    void aFreshFarContainerBeatsAStaleNearOne() {
        var limits = new StorageLimits(16, 8, 600);
        var index = new StorageIndex(limits);
        index.observe(record(1, 0, 0, 399, 1, Map.of("minecraft:iron_ingot", 5)));
        index.observe(record(8, 0, 0, 990, 1, Map.of("minecraft:iron_ingot", 5)));
        var target = StorageTargets.take(index, OVERWORLD, 0, 64, 0, Set.of("minecraft:iron_ingot"), 1, 1_000, limits)
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
        var target = StorageTargets.take(index, OVERWORLD, 0, 64, 0, Set.of("minecraft:iron_ingot"), 1, 1_000, limits)
                .orElseThrow();
        assertEquals(OVERWORLD, target.key().dimension());
        assertFalse(StorageTargets.take(
                        index, "minecraft:the_end", 0, 64, 0, Set.of("minecraft:iron_ingot"), 1, 1_000, limits)
                .isPresent());
    }

    @Test
    void storePrefersCountedRoomAndSkipsACountedFullContainer() {
        var limits = new StorageLimits(16, 8, 600);
        var index = new StorageIndex(limits);
        index.observe(record(2, 0, 0, 900, 27, Map.of("minecraft:stone", 64)));
        index.observe(record(4, 0, 0, 900, 20, Map.of()));
        index.observe(unknown(8, 0, 0, 900));
        assertEquals(
                List.of(4, 8), xs(StorageTargets.storeOrder(index, OVERWORLD, 0, 0, 0, ANDESITE, 1_000, limits, 16)));
    }

    @Test
    void aCountedFullContainerThatHoldsTheItemIsAHintBeforeUnknownCapacity() {
        var limits = new StorageLimits(16, 8, 600);
        var index = new StorageIndex(limits);
        index.observe(unknown(1, 0, 0, 900));
        index.observe(record(5, 0, 0, 900, 27, Map.of("minecraft:iron_ingot", 10)));
        assertEquals(
                List.of(5, 1),
                xs(StorageTargets.storeOrder(
                        index, OVERWORLD, 0, 0, 0, Set.of("minecraft:iron_ingot"), 1_000, limits, 16)));
    }

    @Test
    void storeStaysInsideTheTravelRadius() {
        var limits = new StorageLimits(16, 8, 600);
        var index = new StorageIndex(limits);
        index.observe(unknown(40, 0, 0, 900));
        index.observe(record(60, 0, 0, 900, 0, Map.of()));
        assertTrue(StorageTargets.storeOrder(index, OVERWORLD, 0, 0, 0, ANDESITE, 1_000, limits, 16)
                .isEmpty());
        assertEquals(
                List.of(60, 40), xs(StorageTargets.storeOrder(index, OVERWORLD, 0, 0, 0, ANDESITE, 1_000, limits, 64)));
    }

    @Test
    void depositOrderPutsTheItemThenCountedRoomThenUnknownThenFull() {
        var limits = new StorageLimits(16, 8, 600);
        var index = new StorageIndex(limits);
        index.observe(record(1, 0, 0, 10, 27, Map.of("minecraft:stone", 64)));
        index.observe(record(2, 0, 0, 10, 1, Map.of("minecraft:iron_ingot", 5)));
        index.observe(record(3, 0, 0, 10, 1, Map.of()));
        index.observe(unknown(4, 0, 0, 10));
        assertEquals(
                List.of(2, 3, 4, 1),
                xs(StorageTargets.depositOrder(index.records(), OVERWORLD, "minecraft:iron_ingot", 0, 0, 0)));
    }

    private static List<Integer> xs(List<ContainerRecord> records) {
        return records.stream().map(record -> record.key().x()).toList();
    }

    private static ContainerRecord record(int x, int y, int z, long seen, int used, Map<String, Integer> counts) {
        return new ContainerRecord(new StorageKey(OVERWORLD, x, y, z), "chest", seen, used, 27, counts);
    }

    /** A record written before slot counts were persisted: the container is known, its capacity is not. */
    private static ContainerRecord unknown(int x, int y, int z, long seen) {
        return new ContainerRecord(new StorageKey(OVERWORLD, x, y, z), "chest", seen, 0, 0, Map.of());
    }
}
