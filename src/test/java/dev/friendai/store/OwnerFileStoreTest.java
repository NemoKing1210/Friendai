package dev.friendai.store;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OwnerFileStoreTest {
    @TempDir
    Path temp;

    /** Mutable test value; the revision advances with every increment and is not persisted. */
    private static final class Counter {
        private int value;
        private long revision;

        int value() {
            return value;
        }

        long revision() {
            return revision;
        }

        void increment() {
            value++;
            revision++;
        }
    }

    private static final StoreCodec<Counter> COUNTER_CODEC = new StoreCodec<>() {
        @Override
        public Counter load(String json) {
            Counter counter = new Counter();
            if (json != null) counter.value = Integer.parseInt(json.trim());
            return counter;
        }

        @Override
        public String save(Counter counter) {
            return Integer.toString(counter.value);
        }

        @Override
        public long revision(Counter counter) {
            return counter.revision();
        }

        @Override
        public long maxBytes() {
            return 64;
        }
    };

    @Test
    void missingFileDoesNotWrite() {
        Path dir = temp.resolve("store");
        try (OwnerFileStore<Counter> store = OwnerFileStore.withWriterThread(dir, COUNTER_CODEC)) {
            Counter counter = store.book(UUID.randomUUID());
            assertEquals(0, counter.value());
            assertFalse(store.dirty(UUID.randomUUID()));
        }
        assertFalse(Files.exists(dir));
    }

    @Test
    void roundTripRetainsValue() throws IOException {
        Path dir = temp.resolve("roundtrip");
        UUID owner = UUID.randomUUID();
        ManualExecutor executor = new ManualExecutor();
        try (OwnerFileStore<Counter> store = new OwnerFileStore<>(dir, COUNTER_CODEC, executor, () -> 0L, false)) {
            store.book(owner).increment();
            store.book(owner).increment();
            store.flushIfDue(OwnerFileStore.FLUSH_INTERVAL_MS);
            executor.runNext();
            assertFalse(store.dirty(owner));
        }
        assertTrue(Files.exists(dir.resolve(owner + ".json")));
        try (OwnerFileStore<Counter> store = new OwnerFileStore<>(dir, COUNTER_CODEC, executor, () -> 0L, false)) {
            assertEquals(2, store.book(owner).value());
            assertFalse(store.dirty(owner));
        }
    }

    @Test
    void corruptionIsBackedUpAndTheBookStartsEmpty() throws IOException {
        Path dir = temp.resolve("store");
        Files.createDirectories(dir);
        UUID owner = UUID.randomUUID();
        Files.writeString(dir.resolve(owner + ".json"), "{not json");
        try (OwnerFileStore<Counter> store = OwnerFileStore.withWriterThread(dir, COUNTER_CODEC)) {
            assertEquals(0, store.book(owner).value());
        }
        assertTrue(Files.exists(dir.resolve(owner + ".json.bak")));
    }

    @Test
    void oversizedInputRejectedBeforeParsing() throws IOException {
        Path dir = temp.resolve("oversized");
        Files.createDirectories(dir);
        UUID owner = UUID.randomUUID();
        Files.write(dir.resolve(owner + ".json"), new byte[65]);
        AtomicBoolean parsed = new AtomicBoolean();
        StoreCodec<Counter> counting = new StoreCodec<>() {
            @Override
            public Counter load(String json) {
                parsed.set(json != null);
                return COUNTER_CODEC.load(json);
            }

            @Override
            public String save(Counter counter) {
                return COUNTER_CODEC.save(counter);
            }

            @Override
            public long revision(Counter counter) {
                return COUNTER_CODEC.revision(counter);
            }

            @Override
            public long maxBytes() {
                return COUNTER_CODEC.maxBytes();
            }
        };
        try (OwnerFileStore<Counter> store = OwnerFileStore.withWriterThread(dir, counting)) {
            assertEquals(0, store.book(owner).value());
        }
        assertFalse(parsed.get());
        assertTrue(Files.exists(dir.resolve(owner + ".json.bak")));
    }

    @Test
    void newRevisionDuringSaveStaysDirty() {
        Path dir = temp.resolve("dirty");
        ManualExecutor executor = new ManualExecutor();
        UUID owner = UUID.randomUUID();
        try (OwnerFileStore<Counter> store = new OwnerFileStore<>(dir, COUNTER_CODEC, executor, () -> 0L, false)) {
            store.book(owner).increment();
            store.flushIfDue(OwnerFileStore.FLUSH_INTERVAL_MS);
            store.book(owner).increment();

            executor.runNext();

            assertTrue(store.dirty(owner));
            store.flushIfDue(OwnerFileStore.FLUSH_INTERVAL_MS * 2);
            executor.runNext();
            assertFalse(store.dirty(owner));
        }
    }

    @Test
    void rejectedSaveDoesNotEscapeOrClearDirtyRevision() {
        Executor rejecting = command -> {
            throw new RejectedExecutionException("closed writer");
        };
        UUID owner = UUID.randomUUID();
        try (OwnerFileStore<Counter> store =
                new OwnerFileStore<>(temp.resolve("rejected"), COUNTER_CODEC, rejecting, () -> 5_000L, false)) {
            store.book(owner).increment();
            assertDoesNotThrow(() -> store.flushIfDue(5_000));
            assertTrue(store.dirty(owner));
        }
    }

    @Test
    void ownersAreIsolated() {
        try (OwnerFileStore<Counter> store = OwnerFileStore.withWriterThread(temp.resolve("owners"), COUNTER_CODEC)) {
            UUID first = UUID.randomUUID();
            UUID second = UUID.randomUUID();
            store.book(first).increment();
            assertEquals(1, store.book(first).value());
            assertEquals(0, store.book(second).value());
        }
    }

    @Test
    void flushIfDueRespectsTheDebounceInterval() {
        Path dir = temp.resolve("debounce");
        ManualExecutor executor = new ManualExecutor();
        UUID owner = UUID.randomUUID();
        try (OwnerFileStore<Counter> store = new OwnerFileStore<>(dir, COUNTER_CODEC, executor, () -> 0L, false)) {
            store.book(owner).increment();
            store.flushIfDue(1L);
            assertEquals(0, executor.size());

            store.flushIfDue(OwnerFileStore.FLUSH_INTERVAL_MS);
            assertEquals(1, executor.size());
            executor.runNext();

            store.book(owner).increment();
            store.flushIfDue(OwnerFileStore.FLUSH_INTERVAL_MS + 1L);
            assertEquals(0, executor.size());

            store.flushIfDue(OwnerFileStore.FLUSH_INTERVAL_MS * 2);
            assertEquals(1, executor.size());
        }
    }

    @Test
    void flushIfDueReadsTheClock() {
        long[] now = {0L};
        Path dir = temp.resolve("clock");
        ManualExecutor executor = new ManualExecutor();
        UUID owner = UUID.randomUUID();
        try (OwnerFileStore<Counter> store = new OwnerFileStore<>(dir, COUNTER_CODEC, executor, () -> now[0], false)) {
            store.book(owner).increment();
            store.flushIfDue();
            assertEquals(0, executor.size());

            now[0] = OwnerFileStore.FLUSH_INTERVAL_MS;
            store.flushIfDue();
            assertEquals(1, executor.size());
        }
    }

    private static final class ManualExecutor implements Executor {
        private final Queue<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        void runNext() {
            tasks.remove().run();
        }

        int size() {
            return tasks.size();
        }
    }
}
