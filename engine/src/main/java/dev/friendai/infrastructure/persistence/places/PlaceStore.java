package dev.friendai.infrastructure.persistence.places;

import dev.friendai.features.places.*;
import dev.friendai.foundation.persistence.StoreCodec;
import dev.friendai.foundation.persistence.StoreWriteStatus;
import dev.friendai.infrastructure.persistence.OwnerFileStore;
import dev.friendai.infrastructure.persistence.OwnerStoreWriter;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;

/**
 * Per-owner place books with lazy loading, a five-second debounce and atomic file replacement. A missing file
 * yields an empty registry without touching disk; corrupt files recover only after a successful backup, while
 * unsupported, oversized and unreadable files stay read-only. The behavior lives in {@link OwnerFileStore}.
 */
public final class PlaceStore implements AutoCloseable {
    public static final long FLUSH_INTERVAL_MS = OwnerFileStore.FLUSH_INTERVAL_MS;

    private final OwnerFileStore<PlaceRegistry> store;

    public PlaceStore(Path dir, PlaceLimits limits) {
        this.store = OwnerFileStore.withWriterThread(dir, new PlaceCodec(limits));
    }

    /** Production stores borrow the runtime writer; closing this store never closes it. */
    public PlaceStore(Path dir, PlaceLimits limits, OwnerStoreWriter writer) {
        this(dir, limits, OwnerFileStore.Factory.borrowing(writer));
    }

    public PlaceStore(Path dir, PlaceLimits limits, OwnerFileStore.Factory stores) {
        this.store = stores.create(dir, new PlaceCodec(limits));
    }

    public StoreWriteStatus status(UUID owner) {
        return store.status(owner);
    }

    PlaceStore(Path dir, PlaceLimits limits, Executor writer, LongSupplier clock) {
        this.store = new OwnerFileStore<>(dir, new PlaceCodec(limits), writer, clock, false);
    }

    /** Loads lazily; a missing file creates an empty in-memory book without touching disk. */
    public PlaceRegistry book(UUID owner) {
        return store.book(owner);
    }

    /** Flushes dirty books after the debounce interval; only one immutable snapshot per owner may be queued. */
    public void flushIfDue(long nowMillis) {
        store.flushIfDue(nowMillis);
    }

    public void flushIfDue() {
        store.flushIfDue();
    }

    /** Schedules the latest snapshot of every dirty owner without waiting for disk. */
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

    /** Bounded {@link PlaceFile} format for one owner's places; a null file yields an empty book. */
    private static final class PlaceCodec implements StoreCodec<PlaceRegistry> {
        private final PlaceLimits limits;

        PlaceCodec(PlaceLimits limits) {
            this.limits = limits;
        }

        @Override
        public PlaceRegistry load(String json) {
            if (json == null) {
                return new PlaceRegistry(limits);
            }
            return PlaceFile.decode(json, limits);
        }

        @Override
        public String save(PlaceRegistry registry) {
            return PlaceFile.encode(registry).toJson();
        }

        @Override
        public long revision(PlaceRegistry registry) {
            return registry.revision();
        }

        @Override
        public long maxBytes() {
            return limits.maxFileBytes();
        }
    }
}
