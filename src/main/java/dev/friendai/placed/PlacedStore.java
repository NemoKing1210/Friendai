package dev.friendai.placed;

import dev.friendai.store.OwnerFileStore;
import dev.friendai.store.StoreCodec;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;

/**
 * Per-owner placement ledgers with lazy loading, a five-second debounce and atomic file replacement. A missing
 * file yields an empty book without touching disk; an unreadable file is copied to {@code .bak} and replaced by an
 * empty book. The behavior lives in {@link OwnerFileStore}; this class binds it to {@link PlacedFile}.
 */
public final class PlacedStore implements AutoCloseable {
    public static final long FLUSH_INTERVAL_MS = OwnerFileStore.FLUSH_INTERVAL_MS;

    private final OwnerFileStore<PlacedBook> store;

    public PlacedStore(Path dir, PlacedLimits limits) {
        this.store = OwnerFileStore.withWriterThread(dir, new PlacedCodec(limits));
    }

    PlacedStore(Path dir, PlacedLimits limits, Executor writer, LongSupplier clock) {
        this.store = new OwnerFileStore<>(dir, new PlacedCodec(limits), writer, clock, false);
    }

    /** Loads lazily; a missing file creates an empty in-memory book without touching disk. */
    public PlacedBook book(UUID owner) {
        return store.book(owner);
    }

    public void flushIfDue(long nowMillis) {
        store.flushIfDue(nowMillis);
    }

    public void flushIfDue() {
        store.flushIfDue();
    }

    /** Schedules the latest snapshot of every dirty owner. */
    public void flushAll() {
        store.flushAll();
    }

    /** True when the in-memory book has revisions the disk does not. */
    public boolean dirty(UUID owner) {
        return store.dirty(owner);
    }

    @Override
    public void close() {
        store.close();
    }

    private static final class PlacedCodec implements StoreCodec<PlacedBook> {
        private final PlacedLimits limits;

        PlacedCodec(PlacedLimits limits) {
            this.limits = limits;
        }

        @Override
        public PlacedBook load(String json) {
            return PlacedFile.decode(json, limits);
        }

        @Override
        public String save(PlacedBook book) {
            return PlacedFile.encode(book).toJson();
        }

        @Override
        public long revision(PlacedBook book) {
            return book.revision();
        }

        @Override
        public long maxBytes() {
            return limits.maxFileBytes();
        }
    }
}
