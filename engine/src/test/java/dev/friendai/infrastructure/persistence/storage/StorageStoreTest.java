package dev.friendai.infrastructure.persistence.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.features.storage.ContainerRecord;
import dev.friendai.features.storage.StorageIndex;
import dev.friendai.features.storage.StorageKey;
import dev.friendai.features.storage.StorageLimits;
import dev.friendai.infrastructure.persistence.OwnerStoreWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StorageStoreTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-0000000000c2");

    @TempDir
    Path dir;

    @Test
    void aMissingRecordIsEmptyAndSaveRoundTrips() {
        try (var writer = new OwnerStoreWriter();
                var store = new StorageStore(dir, () -> StorageLimits.DEFAULTS, writer)) {
            assertTrue(store.index(OWNER).records().isEmpty());
            var index = new StorageIndex();
            index.observe(new ContainerRecord(
                    new StorageKey("minecraft:overworld", 0, 64, 0),
                    "chest",
                    7,
                    1,
                    27,
                    Map.of("minecraft:iron_ingot", 5)));
            store.save(OWNER, index);
            store.flushAll();
            assertEquals(1, store.status(OWNER).currentRevision());
        }
        try (var writer = new OwnerStoreWriter();
                var reopened = new StorageStore(dir, () -> StorageLimits.DEFAULTS, writer)) {
            assertEquals(1, reopened.index(OWNER).size());
            assertEquals(
                    5,
                    reopened.index(OWNER)
                            .record(new StorageKey("minecraft:overworld", 0, 64, 0))
                            .orElseThrow()
                            .countOf(Set.of("minecraft:iron_ingot")));
        }
    }

    @Test
    void aNewerOuterVersionIsLeftUnread() throws Exception {
        Files.writeString(dir.resolve(OWNER + ".json"), "{\"formatVersion\":2,\"revision\":1,\"payload\":null}");
        try (var writer = new OwnerStoreWriter();
                var store = new StorageStore(dir, () -> StorageLimits.DEFAULTS, writer)) {
            assertTrue(store.index(OWNER).records().isEmpty());
        }
    }

    @Test
    void anUnchangedPayloadIsNotRewritten() {
        try (var writer = new OwnerStoreWriter();
                var store = new StorageStore(dir, () -> StorageLimits.DEFAULTS, writer)) {
            var index = new StorageIndex();
            index.observe(new ContainerRecord(
                    new StorageKey("minecraft:overworld", 0, 64, 0),
                    "chest",
                    7,
                    1,
                    27,
                    Map.of("minecraft:stone", 1)));
            store.save(OWNER, index);
            store.flushAll();
            long revision = store.status(OWNER).currentRevision();
            store.save(OWNER, index);
            store.flushAll();
            assertEquals(revision, store.status(OWNER).currentRevision());
        }
    }
}
