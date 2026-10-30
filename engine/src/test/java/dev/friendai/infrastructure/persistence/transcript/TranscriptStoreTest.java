package dev.friendai.infrastructure.persistence.transcript;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.features.memory.transcript.Speaker;
import dev.friendai.features.memory.transcript.TranscriptEntry;
import dev.friendai.features.memory.transcript.TranscriptLimits;
import dev.friendai.foundation.persistence.StoreReadOnlyReason;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TranscriptStoreTest {
    @TempDir
    Path temp;

    private static void say(TranscriptStore store, UUID owner, String text) {
        store.book(owner).add(UUID.randomUUID(), "Alex", Speaker.PLAYER, text, 1_000);
    }

    @Test
    void missingFileWritesNothing() {
        Path dir = temp.resolve("transcript");
        try (TranscriptStore store = new TranscriptStore(dir, TranscriptLimits.DEFAULTS)) {
            assertTrue(store.book(UUID.randomUUID()).snapshot().isEmpty());
        }
        assertFalse(Files.exists(dir));
    }

    @Test
    void transcriptSurvivesARestart() {
        Path dir = temp.resolve("transcript");
        UUID owner = UUID.randomUUID();
        try (TranscriptStore store = new TranscriptStore(dir, TranscriptLimits.DEFAULTS)) {
            say(store, owner, "hello");
            store.flushAll();
        }
        try (TranscriptStore store = new TranscriptStore(dir, TranscriptLimits.DEFAULTS)) {
            assertEquals(
                    List.of("hello"),
                    store.book(owner).snapshot().stream()
                            .map(TranscriptEntry::text)
                            .toList());
        }
    }

    @Test
    void futureVersionStaysUntouchedAndReadOnly() throws IOException {
        Path dir = temp.resolve("transcript");
        Files.createDirectories(dir);
        UUID owner = UUID.randomUUID();
        String future = "{\"version\":99,\"nextId\":1,\"entries\":[]}";
        Files.writeString(dir.resolve(owner + ".json"), future);
        try (TranscriptStore store = new TranscriptStore(dir, TranscriptLimits.DEFAULTS)) {
            say(store, owner, "hello");
            assertEquals(StoreReadOnlyReason.UNSUPPORTED, store.status(owner).readOnlyReason());
            store.flushAll();
        }
        assertEquals(future, Files.readString(dir.resolve(owner + ".json")));
    }

    @Test
    void corruptFileIsBackedUpBeforeTheBookStartsEmpty() throws IOException {
        Path dir = temp.resolve("transcript");
        Files.createDirectories(dir);
        UUID owner = UUID.randomUUID();
        Files.writeString(dir.resolve(owner + ".json"), "{\"version\":0,\"entries\":[]}");
        try (TranscriptStore store = new TranscriptStore(dir, TranscriptLimits.DEFAULTS)) {
            assertTrue(store.book(owner).snapshot().isEmpty());
            assertEquals(StoreReadOnlyReason.CORRUPT, store.status(owner).readOnlyReason());
        }
        assertTrue(Files.exists(dir.resolve(owner + ".json.bak")));
    }

    @Test
    void oversizedFileIsNeverOverwritten() throws IOException {
        Path dir = temp.resolve("transcript");
        Files.createDirectories(dir);
        UUID owner = UUID.randomUUID();
        TranscriptLimits tiny = new TranscriptLimits(8, 40, 10_000, 64);
        String huge = "x".repeat(65);
        Files.writeString(dir.resolve(owner + ".json"), huge);
        try (TranscriptStore store = new TranscriptStore(dir, tiny)) {
            say(store, owner, "hello");
            assertEquals(StoreReadOnlyReason.OVERSIZED, store.status(owner).readOnlyReason());
            store.flushAll();
        }
        assertEquals(huge, Files.readString(dir.resolve(owner + ".json")));
    }

    @Test
    void aLoweredCapAppliesToAnExistingFile() {
        Path dir = temp.resolve("transcript");
        UUID owner = UUID.randomUUID();
        try (TranscriptStore store = new TranscriptStore(dir, TranscriptLimits.DEFAULTS)) {
            for (int i = 0; i < 6; i++) say(store, owner, "line " + i);
            store.flushAll();
        }
        TranscriptLimits lowered = new TranscriptLimits(3, 400, 0, 2L * 1024 * 1024);
        try (TranscriptStore store = new TranscriptStore(dir, lowered)) {
            assertEquals(3, store.book(owner).snapshot().size());
            assertEquals("line 5", store.book(owner).snapshot().getLast().text());
        }
    }

    @Test
    void ownerFilesAreIsolated() {
        Path dir = temp.resolve("transcript");
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        try (TranscriptStore store = new TranscriptStore(dir, TranscriptLimits.DEFAULTS)) {
            say(store, first, "hello");
            assertTrue(store.book(second).snapshot().isEmpty());
        }
    }
}
