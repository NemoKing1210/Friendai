package dev.friendai.infrastructure.persistence.transcript;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.features.memory.transcript.Speaker;
import dev.friendai.features.memory.transcript.TranscriptBook;
import dev.friendai.features.memory.transcript.TranscriptEntry;
import dev.friendai.features.memory.transcript.TranscriptLimits;
import dev.friendai.foundation.persistence.StoreFailureKind;
import dev.friendai.foundation.persistence.StoreReadOnlyReason;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
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
            store.applyLimits(2_000);
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
    void aLoweredCapReachesABookThatIsAlreadyLoaded() {
        Path dir = temp.resolve("transcript");
        UUID owner = UUID.randomUUID();
        AtomicReference<TranscriptLimits> limits =
                new AtomicReference<>(new TranscriptLimits(10, 40, 10_000, 2L * 1024 * 1024));
        try (TranscriptStore store = new TranscriptStore(dir, limits::get)) {
            for (int i = 0; i < 6; i++) {
                say(store, owner, "line " + i);
            }
            TranscriptBook live = store.book(owner);
            long nextId = live.nextId();

            limits.set(new TranscriptLimits(3, 5, 10_000, 2L * 1024 * 1024));
            store.applyLimits(2_000);

            assertSame(live, store.book(owner), "the loaded book keeps its identity");
            assertEquals(
                    List.of(4L, 5L, 6L),
                    live.snapshot().stream().map(TranscriptEntry::id).toList());
            assertEquals("line…", live.snapshot().getLast().text());
            assertEquals(
                    nextId,
                    live.add(owner, "Alex", Speaker.PLAYER, "next", 2_500).id());
            store.flushAll();
        }
        try (TranscriptStore store = new TranscriptStore(dir, new TranscriptLimits(3, 5, 10_000, 2L * 1024 * 1024))) {
            assertEquals(
                    List.of(5L, 6L, 7L),
                    store.book(owner).snapshot().stream()
                            .map(TranscriptEntry::id)
                            .toList());
        }
    }

    @Test
    void aFileCapTooSmallForTheSnapshotFailsTypedAndKeepsTheData() {
        Path dir = temp.resolve("transcript");
        UUID owner = UUID.randomUUID();
        AtomicReference<TranscriptLimits> limits =
                new AtomicReference<>(new TranscriptLimits(10, 40, 0, 2L * 1024 * 1024));
        try (TranscriptStore store = new TranscriptStore(dir, limits::get)) {
            say(store, owner, "hello");
            limits.set(new TranscriptLimits(10, 40, 0, 8));
            store.applyLimits(2_000);
            store.flushAll();

            assertEquals(StoreFailureKind.WRITE_FAILED, store.status(owner).failure());
            assertTrue(store.dirty(owner), "the unwritten revision stays dirty");
            assertEquals("hello", store.book(owner).snapshot().getFirst().text());
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
