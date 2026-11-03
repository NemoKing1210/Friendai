package dev.friendai.features.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StorageTextTest {
    @Test
    void noRecordNeverClaimsAbsence() {
        assertEquals(
                "I have no record of minecraft:iron_ingot; I have not looked inside a container that had it",
                StorageText.noRecord("minecraft:iron_ingot"));
    }

    @Test
    void foundRowsCarryCoordinatesAgeAndStaleMark() {
        var key = new StorageKey("minecraft:overworld", 12, 64, -3);
        var fresh = new StoredCandidate(
                new ContainerRecord(key, "chest", 880, 1, 27, Map.of("minecraft:iron_ingot", 32)), 32, 120, false);
        var stale = new StoredCandidate(
                new ContainerRecord(
                        new StorageKey("minecraft:overworld", 1, 64, 2),
                        "barrel",
                        200,
                        1,
                        27,
                        Map.of("minecraft:iron_ingot", 5)),
                5,
                800,
                true);
        String text = StorageText.found("minecraft:iron_ingot", 37, List.of(fresh, stale));
        assertTrue(
                text.startsWith("37 minecraft:iron_ingot recorded: 32 in a chest at 12 64 -3 "
                        + "(minecraft:overworld, checked 120 ticks ago)"),
                text);
        assertTrue(
                text.contains("5 in a barrel at 1 64 2 (minecraft:overworld, checked 800 ticks ago, stale)"), text);
    }

    @Test
    void anEmptyFreshOnlyResultSaysEveryRecordIsStale() {
        var stale = new StoredCandidate(
                new ContainerRecord(
                        new StorageKey("minecraft:overworld", 1, 64, 2),
                        "barrel",
                        200,
                        1,
                        27,
                        Map.of("minecraft:iron_ingot", 5)),
                5,
                800,
                true);
        assertTrue(StorageText.foundFreshOnly("minecraft:iron_ingot", List.of(stale))
                .contains("stale"));
    }
}
