package dev.friendai.infrastructure.persistence.transcript;

import dev.friendai.features.memory.transcript.TranscriptBook;
import dev.friendai.features.memory.transcript.TranscriptFile;
import dev.friendai.features.memory.transcript.TranscriptLimits;
import dev.friendai.foundation.persistence.StoreCodec;
import dev.friendai.foundation.persistence.StoreWriteStatus;
import dev.friendai.infrastructure.persistence.OwnerFilePolicy;
import dev.friendai.infrastructure.persistence.OwnerFileStore;
import dev.friendai.infrastructure.persistence.OwnerStoreWriter;
import java.nio.file.Path;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Per-owner conversation transcripts with lazy loading, a five-second debounce and atomic file replacement. The
 * behavior lives in {@link OwnerFileStore}: a missing file yields an empty book without touching disk, a corrupt
 * file recovers only after a successful backup, and unsupported, oversized and unreadable files stay read-only.
 * The limits are read from a supplier on every decode, so a lowered config cap applies to files already on disk.
 */
public final class TranscriptStore implements AutoCloseable {
    public static final long FLUSH_INTERVAL_MS = OwnerFileStore.FLUSH_INTERVAL_MS;

    private final OwnerFileStore<TranscriptBook> store;

    public TranscriptStore(Path dir, TranscriptLimits limits) {
        this(dir, () -> limits);
    }

    public TranscriptStore(Path dir, Supplier<TranscriptLimits> limits) {
        this.store = new OwnerFileStore<>(
                dir, new Codec(limits), new OwnerStoreWriter(), OwnerFileStore.WriterOwnership.OWNED, new Policy());
    }

    /** Production stores borrow the runtime writer; closing this store never closes it. */
    public TranscriptStore(Path dir, Supplier<TranscriptLimits> limits, OwnerStoreWriter writer) {
        this.store = new OwnerFileStore<>(
                dir, new Codec(limits), writer, OwnerFileStore.WriterOwnership.BORROWED, new Policy());
    }

    /** Loads lazily; a missing file creates an empty in-memory book without touching disk. */
    public TranscriptBook book(UUID owner) {
        return store.book(owner);
    }

    public StoreWriteStatus status(UUID owner) {
        return store.status(owner);
    }

    public void flushIfDue(long nowMillis) {
        store.flushIfDue(nowMillis);
    }

    public void flushIfDue() {
        store.flushIfDue();
    }

    public void flushAll() {
        store.flushAll();
    }

    public boolean dirty(UUID owner) {
        return store.dirty(owner);
    }

    @Override
    public void close() {
        store.close();
    }

    /**
     * A transcript is not worth refusing a conversation for: a file written by a newer mod is left untouched and
     * read-only while the owner talks to an empty in-memory book.
     */
    private static final class Policy implements OwnerFilePolicy<TranscriptBook> {
        @Override
        public boolean emptyOnUnsupportedVersion() {
            return true;
        }
    }

    private static final class Codec implements StoreCodec<TranscriptBook> {
        private final Supplier<TranscriptLimits> limits;

        Codec(Supplier<TranscriptLimits> limits) {
            this.limits = limits;
        }

        @Override
        public TranscriptBook load(String json) {
            return json == null ? new TranscriptBook(limits.get()) : TranscriptFile.decode(json, limits.get());
        }

        @Override
        public String save(TranscriptBook book) {
            return TranscriptFile.encode(book).toJson();
        }

        @Override
        public long revision(TranscriptBook book) {
            return book.revision();
        }

        @Override
        public long maxBytes() {
            return limits.get().maxFileBytes();
        }
    }
}
