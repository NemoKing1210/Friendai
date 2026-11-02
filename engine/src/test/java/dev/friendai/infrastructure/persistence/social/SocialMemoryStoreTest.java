package dev.friendai.infrastructure.persistence.social;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.features.social.SocialMemorySave;
import dev.friendai.infrastructure.persistence.OwnerStoreWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SocialMemoryStoreTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final SocialMemorySave SAVED =
            new SocialMemorySave(List.of("a raised subject", "another subject"), Map.of("a raised subject", 2));

    @TempDir
    Path dir;

    @Test
    void missingRecordIsLazyAndSaveRoundTripsWithRevision() throws Exception {
        try (var writer = new OwnerStoreWriter();
                var store = new SocialMemoryStore(dir, writer)) {
            assertEquals(SocialMemorySave.EMPTY, store.state(OWNER));
            assertFalse(Files.exists(dir.resolve(OWNER + ".json")));
            store.save(OWNER, SAVED);
            assertEquals(1, store.status(OWNER).currentRevision());
            store.save(OWNER, SAVED);
            assertEquals(1, store.status(OWNER).currentRevision(), "an unchanged snapshot is not written again");
        }
        try (var writer = new OwnerStoreWriter();
                var reopened = new SocialMemoryStore(dir, writer)) {
            assertEquals(SAVED, reopened.state(OWNER));
        }
    }

    @Test
    void corruptRecordReadsEmptyAndIsBackedUpInsteadOfThrowing() throws Exception {
        Files.createDirectories(dir);
        Path record = dir.resolve(OWNER + ".json");
        Files.writeString(record, "{not a social memory record");

        try (var writer = new OwnerStoreWriter();
                var store = new SocialMemoryStore(dir, writer)) {
            assertEquals(SocialMemorySave.EMPTY, store.state(OWNER));
            store.save(OWNER, SAVED);
        }
        assertTrue(Files.exists(dir.resolve(OWNER + ".json.bak")));
    }

    @Test
    void newerRecordReadsEmptyAndIsNeverOverwritten() throws Exception {
        Files.createDirectories(dir);
        Path record = dir.resolve(OWNER + ".json");
        String newer = "{\"formatVersion\":9,\"revision\":4,\"payload\":\"future\"}";
        Files.writeString(record, newer);

        try (var writer = new OwnerStoreWriter();
                var store = new SocialMemoryStore(dir, writer)) {
            assertEquals(SocialMemorySave.EMPTY, store.state(OWNER));
            store.save(OWNER, SAVED);
            store.flushAll();
        }
        assertEquals(newer, Files.readString(record));
    }
}
