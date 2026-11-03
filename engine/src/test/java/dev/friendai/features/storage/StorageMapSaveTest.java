package dev.friendai.features.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StorageMapSaveTest {
    @Test
    void aRoundTripKeepsRecordsCountsAndGeneration() {
        var index = new StorageIndex(new StorageLimits(16, 8, 600));
        index.observe(new ContainerRecord(
                new StorageKey("minecraft:overworld", 4, 64, -7),
                "chest",
                1234,
                2,
                27,
                Map.of("minecraft:iron_ingot", 5, "minecraft:stone", 2)));
        index.setGeneration(9);
        var decoded = StorageMapSave.decode(StorageMapSave.encode(index), StorageLimits.DEFAULTS)
                .orElseThrow();
        assertEquals(9, decoded.generation());
        assertEquals(1, decoded.size());
        assertEquals(
                5,
                decoded.record(new StorageKey("minecraft:overworld", 4, 64, -7))
                        .orElseThrow()
                        .countOf(Set.of("minecraft:iron_ingot")));
    }

    @Test
    void corruptNewerOversizedAndInvalidRowsDecodeSafely() {
        assertTrue(StorageMapSave.decode("{", StorageLimits.DEFAULTS).isEmpty());
        assertTrue(StorageMapSave.decode("{\"formatVersion\":2}", StorageLimits.DEFAULTS).isEmpty());
        assertTrue(StorageMapSave.decode("x".repeat(StorageMapSave.MAX_INPUT + 1), StorageLimits.DEFAULTS)
                .isEmpty());

        String json = """
                {"formatVersion":1,"generation":1,"containers":[
                  {"dimension":"minecraft:overworld","x":0,"y":64,"z":0,"kind":"chest","seen":1,
                   "used":1,"total":27,"counts":[{"id":"minecraft:stone","count":0}]},
                  {"dimension":"minecraft:overworld","x":1,"y":64,"z":0,"kind":"chest","seen":1,
                   "used":1,"total":27,"counts":[{"id":"minecraft:dirt","count":2}]}
                ]}""";
        var decoded = StorageMapSave.decode(json, StorageLimits.DEFAULTS).orElseThrow();
        assertEquals(1, decoded.size());
        assertEquals(
                2,
                decoded.record(new StorageKey("minecraft:overworld", 1, 64, 0))
                        .orElseThrow()
                        .countOf(Set.of("minecraft:dirt")));
    }

    @Test
    void theLimitsApplyDuringDecode() {
        StringBuilder json = new StringBuilder("{\"formatVersion\":1,\"generation\":1,\"containers\":[");
        for (int x = 0; x < 17; x++) {
            if (x > 0) {
                json.append(',');
            }
            json.append("{\"dimension\":\"minecraft:overworld\",\"x\":")
                    .append(x)
                    .append(",\"y\":64,\"z\":0,\"kind\":\"chest\",\"seen\":1,\"used\":1,\"total\":27,")
                    .append("\"counts\":[{\"id\":\"minecraft:stone\",\"count\":1}]}");
        }
        json.append("]}");
        var decoded = StorageMapSave.decode(json.toString(), new StorageLimits(16, 8, 600))
                .orElseThrow();
        assertEquals(16, decoded.size());
    }
}
