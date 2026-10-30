package dev.friendai.infrastructure.persistence.memory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.friendai.features.memory.MemoryBook;
import dev.friendai.features.memory.MemoryRecord;
import dev.friendai.features.memory.MemoryRepository;
import dev.friendai.foundation.persistence.StoreCodec;
import dev.friendai.foundation.persistence.StoreReadOnlyReason;
import dev.friendai.foundation.persistence.StoreWriteStatus;
import dev.friendai.foundation.persistence.UnsupportedStoreVersionException;
import dev.friendai.infrastructure.persistence.OwnerFileStore;
import dev.friendai.infrastructure.persistence.OwnerStoreWriter;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;

/** Lazy owner memory cache with asynchronous snapshots through the runtime writer. Server thread only. */
public final class MemoryStore implements MemoryRepository, AutoCloseable {
    public static final long FLUSH_INTERVAL_MS = OwnerFileStore.FLUSH_INTERVAL_MS;
    private static final Gson GSON =
            new GsonBuilder().setPrettyPrinting().serializeNulls().create();
    private final LongSupplier clock;
    private final OwnerFileStore<MemoryBook> store;
    private final Set<UUID> corruptionNotices = new HashSet<>();
    /** Owners whose first load already decided whether a corruption notice is due. */
    private final Set<UUID> loaded = new HashSet<>();

    /** Standalone store for tests; it owns its writer. */
    public MemoryStore(Path dir, LongSupplier clock) {
        this.clock = clock;
        this.store = OwnerFileStore.withWriterThread(dir, new Codec());
    }

    /** Production store borrows the single server-runtime writer. */
    public MemoryStore(Path dir, OwnerStoreWriter writer) {
        this.clock = System::currentTimeMillis;
        this.store = OwnerFileStore.Factory.borrowing(writer).create(dir, new Codec());
    }

    @Override
    public synchronized MemoryBook book(UUID owner) {
        MemoryBook book = store.book(owner);
        // Corrupt recovery is decided once, by the load itself; repeated calls are cache hits with no file I/O.
        if (loaded.add(owner) && store.status(owner).readOnlyReason() == StoreReadOnlyReason.CORRUPT)
            corruptionNotices.add(owner);
        return book;
    }

    public synchronized boolean takeCorruptionNotice(UUID owner) {
        return corruptionNotices.remove(owner);
    }

    public void flushIfDue() {
        store.flushIfDue(clock.getAsLong());
    }

    public void flushIfDue(long nowMillis) {
        store.flushIfDue(nowMillis);
    }

    public void flushAll() {
        store.flushAll();
    }

    public StoreWriteStatus status(UUID owner) {
        return store.status(owner);
    }

    @Override
    public void close() {
        store.close();
    }

    private static final class Codec implements StoreCodec<MemoryBook> {
        private static final long MAX_BYTES = 8L * 1024 * 1024;

        @Override
        public MemoryBook load(String json) {
            if (json == null) return new MemoryBook();
            MemoryRecord record = GSON.fromJson(json, MemoryRecord.class);
            if (record == null) throw new IllegalArgumentException("Empty memory file.");
            if (record.formatVersion() != MemoryRecord.FORMAT_VERSION) throw new UnsupportedStoreVersionException();
            return MemoryBook.fromRecord(record);
        }

        @Override
        public String save(MemoryBook book) {
            return GSON.toJson(MemoryRecord.capture(book));
        }

        @Override
        public long revision(MemoryBook book) {
            return book.revision();
        }

        @Override
        public long maxBytes() {
            return MAX_BYTES;
        }
    }
}
