package dev.friendai.knowledge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.friendai.store.OwnerFileStore;
import dev.friendai.store.StoreCodec;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;

/** Per-owner knowledge cache with bounded, serialized snapshots and atomic file replacement. */
public final class KnowledgeStore implements AutoCloseable {
    public static final long FLUSH_INTERVAL_MS = OwnerFileStore.FLUSH_INTERVAL_MS;
    public static final long MAX_FILE_BYTES = 8L * 1024 * 1024;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().serializeNulls().create();

    private final OwnerFileStore<ResourceKnowledge> store;

    public KnowledgeStore(Path dir, KnowledgeLimits limits) {
        this.store = OwnerFileStore.withWriterThread(dir, new KnowledgeCodec(limits));
    }

    KnowledgeStore(Path dir, KnowledgeLimits limits, Executor writer, LongSupplier clock) {
        this.store = new OwnerFileStore<>(dir, new KnowledgeCodec(limits), writer, clock, false);
    }

    /** Loads lazily; a missing file creates an empty in-memory book without touching disk. */
    public ResourceKnowledge book(UUID owner) { return store.book(owner); }

    /** Flushes dirty books after the debounce interval; only one immutable snapshot per owner may be queued. */
    public void flushIfDue(long nowMillis) { store.flushIfDue(nowMillis); }

    public void flushIfDue() { store.flushIfDue(); }

    /** Schedules the latest snapshot of every dirty owner. Production stores wait through pending revisions. */
    public void flushAll() { store.flushAll(); }

    synchronized boolean dirty(UUID owner) { return store.dirty(owner); }

    @Override
    public void close() { store.close(); }

    /** Bounded GSON format for one owner's atlas; a null file yields an empty book. */
    private static final class KnowledgeCodec implements StoreCodec<ResourceKnowledge> {
        private final KnowledgeLimits limits;

        KnowledgeCodec(KnowledgeLimits limits) { this.limits = limits; }

        @Override
        public ResourceKnowledge load(String json) {
            if (json == null) return new ResourceKnowledge(limits);
            KnowledgeFile parsed = GSON.fromJson(json, KnowledgeFile.class);
            if (parsed == null) throw new IllegalArgumentException("empty knowledge file");
            parsed.migrate();
            return ResourceKnowledge.restore(parsed, limits);
        }

        @Override
        public String save(ResourceKnowledge book) { return GSON.toJson(book.snapshot()); }

        @Override
        public long revision(ResourceKnowledge book) { return book.revision(); }

        @Override
        public long maxBytes() { return MAX_FILE_BYTES; }
    }
}
