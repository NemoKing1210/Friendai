package dev.friendai.infrastructure.persistence;

import dev.friendai.foundation.persistence.*;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lazy owner files with caller-thread snapshot capture and bounded asynchronous atomic replacement.
 * Domain values and revisions are accessed only on the owning thread; writer completions update metadata only.
 */
public final class OwnerFileStore<T> implements AutoCloseable {
    public static final long FLUSH_INTERVAL_MS = 5_000;
    private static final Logger LOGGER = LoggerFactory.getLogger("friendai/store");

    public enum WriterOwnership {
        OWNED,
        BORROWED
    }

    /** Shared construction boundary for domain adapters; file-operation details stay in infrastructure. */
    public interface Factory {
        <V> OwnerFileStore<V> create(Path dir, StoreCodec<V> codec);

        static Factory borrowing(OwnerStoreWriter writer) {
            java.util.Objects.requireNonNull(writer);
            return new Factory() {
                @Override
                public <V> OwnerFileStore<V> create(Path dir, StoreCodec<V> codec) {
                    return new OwnerFileStore<>(dir, codec, writer, WriterOwnership.BORROWED);
                }
            };
        }
    }

    private final Path dir;
    private final StoreCodec<T> codec;
    private final OwnerStoreWriter writer;
    private final WriterOwnership ownership;
    private final LongSupplier clock;
    private final OwnerFileOperations files;
    private final Duration closeTimeout;
    private final boolean waitOnClose;
    private final Map<UUID, T> values = new HashMap<>();
    private final Map<UUID, Long> savedRevision = new HashMap<>();
    private final Map<UUID, Long> failedRevision = new HashMap<>();
    private final Map<UUID, StoreFailureKind> failures = new HashMap<>();
    private final Map<UUID, StoreReadOnlyReason> readOnly = new HashMap<>();
    private final Set<UUID> pending = new HashSet<>();
    private Function<UUID, String> fileNames = id -> id + ".json";
    private long lastFlush;
    private boolean closing;
    private boolean closed;

    /** Standalone/manual-executor test compatibility; executor ownership stays with the caller. */
    public OwnerFileStore(Path dir, StoreCodec<T> codec, Executor executor, LongSupplier clock, boolean waitForWrites) {
        this(
                dir,
                codec,
                new OwnerStoreWriter(executor),
                WriterOwnership.OWNED,
                clock,
                new OwnerFileOperations(),
                Duration.ofSeconds(30),
                waitForWrites);
    }

    public OwnerFileStore(Path dir, StoreCodec<T> codec, OwnerStoreWriter writer, WriterOwnership ownership) {
        this(
                dir,
                codec,
                writer,
                ownership,
                System::currentTimeMillis,
                new OwnerFileOperations(),
                Duration.ofSeconds(30));
    }

    OwnerFileStore(
            Path dir,
            StoreCodec<T> codec,
            OwnerStoreWriter writer,
            WriterOwnership ownership,
            LongSupplier clock,
            OwnerFileOperations files,
            Duration closeTimeout) {
        this(dir, codec, writer, ownership, clock, files, closeTimeout, true);
    }

    private OwnerFileStore(
            Path dir,
            StoreCodec<T> codec,
            OwnerStoreWriter writer,
            WriterOwnership ownership,
            LongSupplier clock,
            OwnerFileOperations files,
            Duration closeTimeout,
            boolean waitOnClose) {
        this.dir = java.util.Objects.requireNonNull(dir);
        this.codec = java.util.Objects.requireNonNull(codec);
        this.writer = java.util.Objects.requireNonNull(writer);
        this.ownership = java.util.Objects.requireNonNull(ownership);
        this.clock = java.util.Objects.requireNonNull(clock);
        this.files = java.util.Objects.requireNonNull(files);
        this.closeTimeout = java.util.Objects.requireNonNull(closeTimeout);
        if (closeTimeout.isNegative()) throw new IllegalArgumentException("Negative close timeout.");
        this.waitOnClose = waitOnClose;
    }

    /** Standalone store that owns its writer. Runtime stores must use an explicitly borrowed writer. */
    public static <T> OwnerFileStore<T> withWriterThread(Path dir, StoreCodec<T> codec) {
        return new OwnerFileStore<>(dir, codec, new OwnerStoreWriter(), WriterOwnership.OWNED);
    }

    public static <T> OwnerFileStore<T> withWriterThread(
            Path dir, StoreCodec<T> codec, Function<UUID, String> fileNames) {
        return withWriterThread(dir, codec).withFileNames(fileNames);
    }

    /** Chooses a fixed safe scope filename before the store is used. */
    public synchronized OwnerFileStore<T> withFileNames(Function<UUID, String> fileNames) {
        if (!values.isEmpty() || !readOnly.isEmpty()) throw new IllegalStateException("Store already loaded.");
        this.fileNames = java.util.Objects.requireNonNull(fileNames);
        return this;
    }

    /** A missing file is clean and absent. Unreadable originals remain read-only; corruption needs a backup. */
    public synchronized T book(UUID owner) {
        if (readOnly.get(owner) == StoreReadOnlyReason.UNSUPPORTED) throw new UnsupportedStoreVersionException();
        T value = values.get(owner);
        if (value != null) return value;
        if (closing || closed) throw new IllegalStateException("Store is closed.");
        Path target = file(owner);
        try {
            if (files.size(target) > codec.maxBytes()) {
                readOnly.put(owner, StoreReadOnlyReason.OVERSIZED);
                backup(owner, target);
                return adopt(owner, fresh());
            }
        } catch (NoSuchFileException absent) {
            return adopt(owner, fresh());
        } catch (IOException | RuntimeException failure) {
            return unreadable(owner);
        }
        OwnerFileOperations.ReadAttempt attempt = new OwnerFileOperations.ReadAttempt();
        try (Reader reader = files.reader(target, codec.maxBytes())) {
            value = codec.load(attempt.observe(reader));
            if (attempt.failure != null) throw attempt.failure;
            if (value == null) throw new IllegalArgumentException("Empty owner file.");
            return adopt(owner, value);
        } catch (UnsupportedStoreVersionException unsupported) {
            readOnly.put(owner, StoreReadOnlyReason.UNSUPPORTED);
            throw unsupported;
        } catch (OwnerFileOperations.OversizedInputException oversized) {
            readOnly.put(owner, StoreReadOnlyReason.OVERSIZED);
            backup(owner, target);
            return adopt(owner, fresh());
        } catch (IOException failure) {
            return unreadable(owner);
        } catch (RuntimeException | StackOverflowError corrupt) {
            // A streaming codec may wrap or swallow the reader's exception. Parser failures alone are corruption.
            if (attempt.failure instanceof OwnerFileOperations.OversizedInputException) {
                readOnly.put(owner, StoreReadOnlyReason.OVERSIZED);
                backup(owner, target);
                return adopt(owner, fresh());
            }
            if (attempt.failure != null) return unreadable(owner);
            backup(owner, target);
            return adopt(owner, fresh());
        }
    }

    private T unreadable(UUID owner) {
        readOnly.put(owner, StoreReadOnlyReason.READ_FAILED);
        failures.put(owner, StoreFailureKind.READ_FAILED);
        return adopt(owner, fresh());
    }

    public synchronized StoreWriteStatus status(UUID owner) {
        T value = values.get(owner);
        long saved = savedRevision.getOrDefault(owner, 0L);
        return new StoreWriteStatus(
                value == null ? saved : codec.revision(value),
                saved,
                pending.contains(owner),
                failures.get(owner),
                readOnly.get(owner));
    }

    /** Ordinary flushes capture snapshots without waiting for disk. */
    public synchronized void flushIfDue(long nowMillis) {
        if (closing || closed) return;
        long now = Math.max(lastFlush, nowMillis);
        if (now - lastFlush < FLUSH_INTERVAL_MS) return;
        lastFlush = now;
        failedRevision.clear();
        queueDirty();
    }

    public void flushIfDue() {
        flushIfDue(clock.getAsLong());
    }

    public synchronized void flushAll() {
        if (closing || closed) return;
        failedRevision.clear();
        queueDirty();
    }

    public synchronized boolean dirty(UUID owner) {
        return status(owner).dirty();
    }

    /** Final caller-thread snapshot/wait loop, bounded even if a borrowed executor never completes. */
    @Override
    public void close() {
        synchronized (this) {
            if (closing || closed) return;
            closing = true;
            failedRevision.clear();
        }
        try {
            if (waitOnClose) awaitFinalWrites();
            else
                synchronized (this) {
                    queueDirty();
                }
        } finally {
            synchronized (this) {
                closed = true;
                closing = false;
            }
            if (ownership == WriterOwnership.OWNED) writer.close();
        }
    }

    private T fresh() {
        return codec.load((String) null);
    }

    private T adopt(UUID owner, T value) {
        values.put(owner, value);
        savedRevision.put(owner, codec.revision(value));
        return value;
    }

    private void queueDirty() {
        for (Map.Entry<UUID, T> entry : values.entrySet()) {
            UUID owner = entry.getKey();
            long revision = codec.revision(entry.getValue());
            if (revision == savedRevision.getOrDefault(owner, 0L)
                    || pending.contains(owner)
                    || readOnly.containsKey(owner)
                    || failedRevision.getOrDefault(owner, Long.MIN_VALUE) == revision) continue;
            final byte[] snapshot;
            final Path target;
            try {
                snapshot = codec.save(entry.getValue()).getBytes(StandardCharsets.UTF_8);
                if (snapshot.length > codec.maxBytes())
                    throw new IllegalArgumentException("Store snapshot exceeds limit.");
                target = file(owner);
            } catch (RuntimeException | StackOverflowError failure) {
                fail(owner, revision, StoreFailureKind.WRITE_FAILED);
                continue;
            }
            pending.add(owner);
            boolean accepted;
            try {
                accepted = writer.trySubmit(() -> save(owner, target, revision, snapshot));
            } catch (RuntimeException failure) {
                accepted = false;
            }
            if (!accepted) {
                pending.remove(owner);
                fail(owner, revision, StoreFailureKind.QUEUE_FULL);
            }
        }
    }

    private void save(UUID owner, Path target, long revision, byte[] snapshot) {
        boolean saved = false;
        Path temporary = null;
        try {
            temporary = files.temporary(target);
            files.write(temporary, snapshot);
            files.replace(temporary, target);
            saved = true;
        } catch (IOException | RuntimeException failure) {
            // Only typed diagnostics escape the I/O layer.
        } finally {
            if (temporary != null) {
                try {
                    files.cleanup(temporary);
                } catch (IOException | RuntimeException ignored) {
                    LOGGER.warn("Could not remove owner store temporary file");
                }
            }
            synchronized (this) {
                pending.remove(owner);
                if (saved) {
                    savedRevision.put(owner, revision);
                    failures.remove(owner);
                    failedRevision.remove(owner);
                } else fail(owner, revision, StoreFailureKind.WRITE_FAILED);
                notifyAll();
            }
        }
    }

    private void fail(UUID owner, long revision, StoreFailureKind failure) {
        failedRevision.put(owner, revision);
        failures.put(owner, failure);
    }

    private void awaitFinalWrites() {
        long deadline = System.nanoTime() + closeTimeout.toNanos();
        synchronized (this) {
            while (true) {
                queueDirty();
                if (pending.isEmpty()) return;
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    markTimeout();
                    return;
                }
                try {
                    wait(Math.max(1, Math.min(50, remaining / 1_000_000)));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    markTimeout();
                    return;
                }
            }
        }
    }

    private void markTimeout() {
        for (UUID owner : pending) failures.put(owner, StoreFailureKind.SHUTDOWN_TIMEOUT);
    }

    private Path file(UUID owner) {
        String name = fileNames.apply(owner);
        if (name == null || !name.matches("[a-zA-Z0-9-]+\\.json"))
            throw new IllegalArgumentException("Invalid store filename.");
        return dir.resolve(name);
    }

    private void backup(UUID owner, Path target) {
        try {
            files.backup(target, target.resolveSibling(target.getFileName() + ".bak"));
        } catch (IOException | RuntimeException failure) {
            readOnly.put(owner, StoreReadOnlyReason.BACKUP_FAILED);
            failures.put(owner, StoreFailureKind.BACKUP_FAILED);
        }
    }
}
