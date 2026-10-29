package dev.friendai.infrastructure.persistence.trading;

import dev.friendai.features.trading.TradeBook;
import dev.friendai.features.trading.TradeBookRepository;
import dev.friendai.features.trading.TradeFile;
import dev.friendai.foundation.persistence.StoreCodec;
import dev.friendai.foundation.persistence.StoreReadOnlyReason;
import dev.friendai.foundation.persistence.StoreWriteStatus;
import dev.friendai.foundation.persistence.UnsupportedStoreVersionException;
import dev.friendai.infrastructure.persistence.OwnerFilePolicy;
import dev.friendai.infrastructure.persistence.OwnerFileStore;
import dev.friendai.infrastructure.persistence.OwnerStoreWriter;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;

/** Owner-scoped trading snapshots using shared file handling and trade-specific bounds. */
public final class TradeStore implements TradeBookRepository, AutoCloseable {
    public static final long FLUSH_INTERVAL_MS = OwnerFileStore.FLUSH_INTERVAL_MS;

    @FunctionalInterface
    interface FileMover {
        void move(Path source, Path target, CopyOption... options) throws IOException;
    }

    @FunctionalInterface
    interface FileCopier {
        void copy(Path source, Path target, CopyOption... options) throws IOException;
    }

    private final OwnerFileStore<TradeBook> store;
    private final LongSupplier clock;

    /** Standalone store that owns its writer. */
    public TradeStore(Path dir) {
        this(dir, new OwnerStoreWriter(), OwnerFileStore.WriterOwnership.OWNED);
    }

    /** Production store borrows the runtime writer. */
    public TradeStore(Path dir, OwnerStoreWriter writer) {
        this(dir, writer, OwnerFileStore.WriterOwnership.BORROWED);
    }

    TradeStore(Path dir, FileMover mover) {
        this(dir, mover, Files::copy);
    }

    TradeStore(Path dir, FileMover mover, FileCopier copier) {
        this.clock = System::currentTimeMillis;
        store = OwnerFileStore.withTestFileOperations(
                dir,
                new Codec(),
                new Policy(),
                new OwnerStoreWriter(),
                OwnerFileStore.WriterOwnership.OWNED,
                clock,
                true,
                mover::move,
                copier::copy);
    }

    TradeStore(Path dir, Executor writer, LongSupplier clock, FileMover mover, boolean waitForWrites) {
        this.clock = Objects.requireNonNull(clock);
        store = OwnerFileStore.withTestFileOperations(
                dir, new Codec(), new Policy(), writer, clock, waitForWrites, mover::move, Files::copy);
    }

    private TradeStore(Path dir, OwnerStoreWriter writer, OwnerFileStore.WriterOwnership ownership) {
        this.clock = System::currentTimeMillis;
        store = new OwnerFileStore<>(dir, new Codec(), writer, ownership, new Policy());
    }

    @Override
    public TradeBook book(UUID owner) {
        return store.book(owner);
    }

    public StoreWriteStatus status(UUID owner) {
        return store.status(owner);
    }

    public void flushIfDue(long nowMillis) {
        store.flushIfDue(nowMillis);
    }

    public void flushIfDue() {
        store.flushIfDue(clock.getAsLong());
    }

    /** Queues dirty books without blocking the server thread. */
    public void flushAll() {
        store.flushAll();
    }

    /** Replaces a read-only original only after a fresh backup succeeds. */
    public boolean repairOwner(UUID owner) {
        return store.repairOwner(owner);
    }

    boolean dirty(UUID owner) {
        return store.dirty(owner);
    }

    boolean writesDisabled(UUID owner) {
        return store.status(owner).readOnlyReason() != null;
    }

    String failure(UUID owner) {
        StoreWriteStatus status = store.status(owner);
        if (status.failure() != null) return status.failure().name();
        if (status.readOnlyReason() != null) return status.readOnlyReason().name();
        return null;
    }

    @Override
    public void close() {
        store.close();
    }

    private static final class Codec implements StoreCodec<TradeBook> {
        @Override
        public TradeBook load(String json) {
            if (json == null) return new TradeBook();
            return decoded(json.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public TradeBook load(Reader reader) throws IOException {
            try {
                return new TradeBook(TradeFile.read(reader));
            } catch (TradeFile.UnsupportedVersionException future) {
                throw new UnsupportedStoreVersionException();
            }
        }

        private TradeBook decoded(byte[] bytes) {
            try {
                return new TradeBook(TradeFile.read(bytes));
            } catch (TradeFile.UnsupportedVersionException future) {
                throw new UnsupportedStoreVersionException();
            }
        }

        @Override
        public String save(TradeBook book) {
            return new String(TradeFile.write(book.snapshot()), StandardCharsets.UTF_8);
        }

        @Override
        public long revision(TradeBook book) {
            return book.revision();
        }

        @Override
        public long maxBytes() {
            return TradeFile.MAX_FILE_BYTES;
        }
    }

    private static final class Policy implements OwnerFilePolicy<TradeBook> {
        @Override
        public byte[] encode(TradeBook book, StoreCodec<TradeBook> codec) {
            while (true) {
                try {
                    return TradeFile.write(book.snapshot());
                } catch (TradeFile.EncodedSizeExceeded tooLarge) {
                    if (book.evictOldestUnprotected()) continue;
                    if (book.evictOldestTerminalProgress()) continue;
                    throw tooLarge;
                }
            }
        }

        @Override
        public boolean emptyOnUnsupportedVersion() {
            return true;
        }

        @Override
        public boolean canRepair(StoreReadOnlyReason reason) {
            return reason == StoreReadOnlyReason.CORRUPT
                    || reason == StoreReadOnlyReason.UNSUPPORTED
                    || reason == StoreReadOnlyReason.OVERSIZED
                    || reason == StoreReadOnlyReason.BACKUP_FAILED;
        }

        @Override
        public boolean backupOversizedOnLoad() {
            return false;
        }
    }
}
