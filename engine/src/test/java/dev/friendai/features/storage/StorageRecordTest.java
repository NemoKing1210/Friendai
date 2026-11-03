package dev.friendai.features.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StorageRecordTest {
    @Test
    void limitsRejectOutOfRangeValues() {
        assertEquals(new StorageLimits(256, 64, 24_000), StorageLimits.DEFAULTS);
        assertThrows(IllegalArgumentException.class, () -> new StorageLimits(15, 64, 24_000));
        assertThrows(IllegalArgumentException.class, () -> new StorageLimits(256, 7, 24_000));
        assertThrows(IllegalArgumentException.class, () -> new StorageLimits(256, 64, 599));
    }

    @Test
    void aRecordSortsCountsAndCountsMatchingIds() {
        var key = new StorageKey("minecraft:overworld", 4, 64, -7);
        var record =
                new ContainerRecord(key, "chest", 10, 2, 27, Map.of("minecraft:stone", 2, "minecraft:iron_ingot", 5));
        assertEquals(
                List.of("minecraft:iron_ingot", "minecraft:stone"),
                List.copyOf(record.counts().keySet()));
        assertEquals(5, record.countOf(Set.of("minecraft:iron_ingot")));
        assertEquals(7, record.countOf(Set.of("minecraft:iron_ingot", "minecraft:stone")));
        assertEquals(25, record.freeSlots());
    }

    @Test
    void invalidRecordsAreRejected() {
        var key = new StorageKey("minecraft:overworld", 0, 0, 0);
        assertThrows(IllegalArgumentException.class, () -> new StorageKey(" ", 0, 0, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ContainerRecord(key, "chest", 1, 0, 27, Map.of("minecraft:stone", 0)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ContainerRecord(key, "chest", 1, 28, 27, Map.of("minecraft:stone", 1)));
        assertThrows(IllegalArgumentException.class, () -> new ContainerRecord(key, " ", 1, 0, 27, Map.of()));
    }
}
