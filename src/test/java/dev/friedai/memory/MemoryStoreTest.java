package dev.friedai.memory;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
        assertTrue(Files.exists(dir.resolve(A + ".json")));
        assertFalse(store.book(A).isDirty());
    }

    @Test
    void flushAllWritesDirtyBooksImmediately() {
        long[] now = {0};
        MemoryStore store = new MemoryStore(dir, () -> now[0]);
        store.book(A).setTrusted(B, "Bob", true, 0);
        store.flushAll();
        assertTrue(new MemoryStore(dir, () -> 0).book(A).isTrusted(B));
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
    }

    @Test
    void writeFailureKeepsBookDirty() throws IOException {
        Path blocker = dir.resolve("file");
        Files.writeString(blocker, "x");
        long[] now = {0};
        MemoryStore bad = new MemoryStore(blocker.resolve("memory"), () -> now[0]);
        bad.book(A).touch(B, "Bob", 0);
        assertDoesNotThrow(bad::flushAll);
        assertTrue(bad.book(A).isDirty());
    }
}
