package dev.friendai.infrastructure.persistence.memory;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.features.memory.*;
import dev.friendai.features.memory.retrieval.LexicalEmbedder;
import dev.friendai.features.memory.retrieval.RetrievalIndex;
import dev.friendai.foundation.persistence.UnsupportedStoreVersionException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MemoryStoreTest {
    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    @TempDir
    Path dir;

    @Test
    void missingFileGivesEmptyBookWithoutWriting() {
        long[] now = {0};
        MemoryStore store = new MemoryStore(dir, () -> now[0]);
        assertTrue(store.book(A).trusted().isEmpty());
        assertFalse(Files.exists(dir.resolve(A + ".json")));
        store.close();
    }

    @Test
    void flushWaitsForInterval() throws IOException {
        long[] now = {0};
        MemoryStore store = new MemoryStore(dir, () -> now[0]);
        store.book(A).setTrusted(B, "Bob", true, 0);
        now[0] = 4_999;
        store.flushIfDue();
        assertFalse(Files.exists(dir.resolve(A + ".json")));
        now[0] = 5_000;
        store.flushIfDue();
        store.close();
        assertTrue(Files.exists(dir.resolve(A + ".json")));
        assertFalse(store.status(A).dirty());
    }

    @Test
    void flushAllWritesDirtyBooksImmediately() {
        long[] now = {0};
        MemoryStore store = new MemoryStore(dir, () -> now[0]);
        store.book(A).setTrusted(B, "Bob", true, 0);
        store.flushAll();
        store.close();
        MemoryStore reopened = new MemoryStore(dir, () -> 0);
        assertTrue(reopened.book(A).isTrusted(B));
        reopened.close();
    }

    @Test
    void corruptFileGivesEmptyBookBackupAndOneNotice() throws IOException {
        Files.writeString(dir.resolve(A + ".json"), "{broken");
        long[] now = {0};
        MemoryStore store = new MemoryStore(dir, () -> now[0]);
        assertTrue(store.book(A).trusted().isEmpty());
        assertTrue(Files.exists(dir.resolve(A + ".json.bak")));
        assertTrue(store.takeCorruptionNotice(A));
        assertFalse(store.takeCorruptionNotice(A));
        store.close();
    }

    @Test
    void cachedBookReadsKeepIdentityRevisionAndOneNotice() throws IOException {
        Files.writeString(dir.resolve(A + ".json"), "{broken");
        MemoryStore store = new MemoryStore(dir, () -> 0);

        MemoryBook first = store.book(A);
        long revision = first.revision();
        MemoryBook second = store.book(A);

        assertSame(first, second, "a cached book must stay the same instance");
        assertEquals(revision, second.revision(), "a cache hit must not change the revision");
        assertTrue(store.takeCorruptionNotice(A), "the first load reports the corrupt file");
        assertFalse(store.takeCorruptionNotice(A), "the notice is one-shot");
        store.book(A);
        assertFalse(store.takeCorruptionNotice(A), "a later cache hit must not raise another notice");
        store.close();
    }

    @Test
    void factsPersistAcrossStores() {
        MemoryStore store = new MemoryStore(dir, () -> 0);
        store.book(A)
                .addFact(Subject.player(B), Category.PROFILE, "Builds farms", A, "Steve", 0, MemoryLimits.DEFAULTS);
        store.flushAll();
        store.close();
        MemoryStore reopened = new MemoryStore(dir, () -> 0);
        assertEquals(1, reopened.book(A).allFacts().size());
        assertEquals("Builds farms", reopened.book(A).allFacts().get(0).text());
        reopened.close();
    }

    @Test
    void foodRulesPersistAcrossStores() {
        MemoryStore store = new MemoryStore(dir, () -> 0);
        store.book(A).setFoodRule("minecraft:rotten_flesh", true);
        store.book(A).setFoodRule("minecraft:bread", false);
        store.flushAll();
        store.close();
        MemoryStore reopened = new MemoryStore(dir, () -> 0);
        assertEquals(Optional.of(true), reopened.book(A).foodRule("minecraft:rotten_flesh"));
        assertEquals(Optional.of(false), reopened.book(A).foodRule("minecraft:bread"));
        reopened.close();
    }

    @Test
    void unsupportedFormatIsReadOnlyAndPreserved() throws IOException {
        Path file = dir.resolve(A + ".json");
        Files.writeString(
                file,
                "{\"formatVersion\":" + (dev.friendai.features.memory.MemoryRecord.FORMAT_VERSION + 1)
                        + ",\"revision\":2}");

        String old = Files.readString(file);
        MemoryStore store = new MemoryStore(dir, () -> 0L);
        assertThrows(UnsupportedStoreVersionException.class, () -> store.book(A));
        store.flushAll();
        assertEquals(old, Files.readString(file));
        store.close();
    }

    @Test
    void formatFourFilesStillLoadAndAreUpgradedOnTheNextWrite() throws IOException {
        Path file = dir.resolve(A + ".json");
        Files.writeString(file, """
                {"formatVersion":4,"revision":3,"nextId":2,"players":[],
                 "facts":[{"id":1,"subject":"world","category":"NOTE","text":"The lake is north","createdAt":0}],
                 "foodRules":[],"bonds":{}}
                """);
        MemoryStore store = new MemoryStore(dir, () -> 0L);
        MemoryBook book = store.book(A);
        assertEquals(1, book.allFacts().size(), "a format 4 file still reads");
        assertEquals("The lake is north", book.allFacts().getFirst().text());
        assertTrue(book.retrievalIndex(A).isEmpty(), "a format 4 file has no derived index and gains none by magic");

        book.setFoodRule("minecraft:bread", false);
        store.flushAll();
        store.close();

        String json = Files.readString(file);
        assertTrue(json.contains("\"formatVersion\": 5"), "the next write is the current format: " + json);
        assertTrue(json.contains("\"retrieval\": {}"), "an old file gains an empty derived section: " + json);
    }

    @Test
    void theDerivedIndexSurvivesARestartAndAForgottenFactLeavesNoVectorsOnDisk() throws IOException {
        MemoryStore store = new MemoryStore(dir, () -> 0L);
        MemoryBook book = store.book(A);
        Fact lake = book.addFact(
                Subject.WORLD, Category.PLACE, "The lake is north of the base", A, "Ann", 0, MemoryLimits.DEFAULTS);
        RetrievalIndex index = RetrievalIndex.build(
                List.of(new RetrievalIndex.Source(lake.id(), lake.text())),
                List.of(lake.text()),
                LexicalEmbedder.ID,
                LexicalEmbedder.DIMENSIONS,
                new LexicalEmbedder());
        assertTrue(book.putRetrievalIndex(A, index));
        store.flushAll();
        store.close();

        MemoryStore reopened = new MemoryStore(dir, () -> 0L);
        MemoryBook restored = reopened.book(A);
        assertEquals(
                index,
                restored.retrievalIndex(A).orElseThrow(),
                "a restart reuses the vectors instead of rebuilding them");

        assertTrue(restored.removeFact(lake.id()));
        assertTrue(restored.retrievalIndex(A).isEmpty(), "a forgotten fact drops the index at once");
        reopened.flushAll();
        reopened.close();

        String json = Files.readString(dir.resolve(A + ".json"));
        assertTrue(json.contains("\"retrieval\": {}"), "a forgotten fact leaves no vectors behind: " + json);
        assertFalse(json.contains(LexicalEmbedder.ID), "the backend stamp went with the index");
    }

    @Test
    void writeFailureKeepsBookDirty() throws IOException {
        Path blocker = dir.resolve("file");
        Files.writeString(blocker, "x");
        long[] now = {0};
        MemoryStore bad = new MemoryStore(blocker.resolve("memory"), () -> now[0]);
        bad.book(A).touch(B, "Bob", 0);
        assertDoesNotThrow(bad::flushAll);
        bad.close();
        assertTrue(bad.status(A).dirty());
    }
}
