package dev.friendai.infrastructure.persistence.trading;

import dev.friendai.features.trading.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Owner-scoped trading snapshots, independent from memory and resource knowledge. */
public final class TradeStore implements TradeBookRepository, AutoCloseable {
    public static final long FLUSH_INTERVAL_MS = 5_000;
    private static final Logger LOGGER = LoggerFactory.getLogger("friendai/trading");

    @FunctionalInterface
    interface FileMover {
        void move(Path source, Path target, CopyOption... options) throws IOException;
    }

    @FunctionalInterface
    interface FileCopier {
        void copy(Path source, Path target, CopyOption... options) throws IOException;
    }

    private final Path dir;
    private final Executor writer;
    private final ExecutorService ownedWriter;
    private final boolean waitForWrites;
    private final LongSupplier clock;
    private final FileMover mover;
    private final FileCopier copier;
    private final Map<UUID, TradeBook> books = new HashMap<>();
    private final Map<UUID, Long> savedRevision = new HashMap<>();
    private final Map<UUID, Long> failedRevision = new HashMap<>();
    private final Map<UUID, String> failures = new HashMap<>();
    private final Set<UUID> pending = new HashSet<>();
    private final Map<UUID, String> writeDisabledOwners = new HashMap<>();
    private long lastFlush;
    private boolean closed;

    public TradeStore(Path dir) {
        this(dir, newWriter(), true, System::currentTimeMillis, Files::move, Files::copy);
    }

    TradeStore(Path dir, FileMover mover) {
        this(dir, newWriter(), true, System::currentTimeMillis, mover, Files::copy);
    }

    TradeStore(Path dir, FileMover mover, FileCopier copier) {
        this(dir, newWriter(), true, System::currentTimeMillis, mover, copier);
    }

    TradeStore(Path dir, Executor writer, LongSupplier clock, FileMover mover, boolean waitForWrites) {
        this(dir, writer, null, waitForWrites, clock, mover, Files::copy);
    }

    private TradeStore(
            Path dir,
            ExecutorService ownedWriter,
            boolean waitForWrites,
            LongSupplier clock,
            FileMover mover,
            FileCopier copier) {
        this(dir, ownedWriter, ownedWriter, waitForWrites, clock, mover, copier);
    }

    private TradeStore(
            Path dir,
            Executor writer,
            ExecutorService ownedWriter,
            boolean waitForWrites,
            LongSupplier clock,
            FileMover mover,
            FileCopier copier) {
        this.dir = Objects.requireNonNull(dir);
        this.writer = Objects.requireNonNull(writer);
        this.ownedWriter = ownedWriter;
        this.waitForWrites = waitForWrites;
        this.clock = Objects.requireNonNull(clock);
        this.mover = Objects.requireNonNull(mover);
        this.copier = Objects.requireNonNull(copier);
    }

    private static ExecutorService newWriter() {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "friendai-trading-writer");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Loads one owner's book on demand; absent files do not cause directory or file creation. */
    public synchronized TradeBook book(UUID owner) {
        Objects.requireNonNull(owner);
        TradeBook loaded = books.get(owner);
        if (loaded != null) return loaded;

        Path file = file(owner);
        if (!Files.exists(file)) {
            TradeBook empty = new TradeBook();
            books.put(owner, empty);
            savedRevision.put(owner, empty.revision());
            return empty;
        }

        try {
            long size = Files.size(file);
            if (size > TradeFile.MAX_FILE_BYTES) {
                disableWrites(owner, "oversized trade file preserved until explicit repair");
                TradeBook empty = new TradeBook();
                books.put(owner, empty);
                savedRevision.put(owner, empty.revision());
                return empty;
            }
            TradeFile snapshot = TradeFile.read(readBounded(file));
            TradeBook restored = new TradeBook(snapshot);
            books.put(owner, restored);
            savedRevision.put(owner, restored.revision());
            return restored;
        } catch (TradeFile.UnsupportedVersionException e) {
            disableWrites(owner, "unsupported future trade file version " + e.version());
            TradeBook empty = new TradeBook();
            books.put(owner, empty);
            savedRevision.put(owner, empty.revision());
            return empty;
        } catch (IOException | RuntimeException | StackOverflowError e) {
            boolean backedUp = backupCorrupt(owner, file, e);
            TradeBook empty = new TradeBook();
            books.put(owner, empty);
            savedRevision.put(owner, backedUp ? empty.revision() : empty.revision() - 1);
            return empty;
        }
    }

    /** Flushes dirty books at most once every five seconds; callers may pass a deterministic clock in tests. */
    public synchronized void flushIfDue(long nowMillis) {
        long now = Math.max(lastFlush, nowMillis);
        if (now - lastFlush < FLUSH_INTERVAL_MS) return;
        lastFlush = now;
        failedRevision.clear();
        queueDirty();
    }

    public void flushIfDue() {
        flushIfDue(clock.getAsLong());
    }

    /** Queues the latest snapshot of every dirty owner; production waits through revisions queued during a save. */
    public void flushAll() {
        synchronized (this) {
            failedRevision.clear();
            queueDirty();
        }
        if (waitForWrites) waitUntilCleanOrFailed();
    }

    private synchronized void queueDirty() {
        owners:
        for (Map.Entry<UUID, TradeBook> entry : books.entrySet()) {
            UUID owner = entry.getKey();
            TradeBook book = entry.getValue();
            if (writeDisabledOwners.containsKey(owner)) {
                fail(owner, book.revision(), writeDisabledOwners.get(owner));
                continue;
            }
            long revision = book.revision();
            if (revision == savedRevision.getOrDefault(owner, 0L)
                    || pending.contains(owner)
                    || failedRevision.getOrDefault(owner, Long.MIN_VALUE) == revision) continue;

            TradeFile snapshot;
            byte[] bytes;
            while (true) {
                snapshot = book.snapshot();
                try {
                    bytes = TradeFile.write(snapshot);
                    break;
                } catch (TradeFile.EncodedSizeExceeded e) {
                    if (book.evictOldestUnprotected()) continue;
                    if (book.evictOldestTerminalProgress()) continue;
                    fail(owner, book.revision(), "protected trade records exceed 8 MiB; previous file retained");
                    continue owners;
                } catch (RuntimeException e) {
                    fail(owner, book.revision(), "could not encode trade snapshot: " + safeMessage(e));
                    continue owners;
                }
            }

            long snapshotRevision = snapshot.revision();
            pending.add(owner);
            try {
                byte[] immutableBytes = bytes;
                writer.execute(() -> save(owner, snapshotRevision, immutableBytes));
            } catch (RuntimeException e) {
                pending.remove(owner);
                fail(owner, snapshotRevision, "could not queue trade file: " + safeMessage(e));
            }
        }
    }

    private void save(UUID owner, long revision, byte[] bytes) {
        boolean saved = false;
        String failure = null;
        Path target = file(owner);
        Path temporary = null;
        try {
            Path parent = target.toAbsolutePath().getParent();
            Files.createDirectories(parent);
            temporary = Files.createTempFile(parent, target.getFileName().toString(), ".tmp");
            Files.write(temporary, bytes);
            try {
                mover.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                mover.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            saved = true;
        } catch (IOException | RuntimeException e) {
            failure = "could not save trade file: " + safeMessage(e);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException e) {
                    LOGGER.warn("Could not remove temporary trade file for owner {}", owner, e);
                }
            }
            synchronized (this) {
                pending.remove(owner);
                if (saved) {
                    savedRevision.put(owner, revision);
                    if (failedRevision.getOrDefault(owner, Long.MIN_VALUE) <= revision) {
                        failedRevision.remove(owner);
                        failures.remove(owner);
                    }
                } else {
                    fail(owner, revision, failure == null ? "unknown trade file write failure" : failure);
                }
                notifyAll();
            }
        }
    }

    private void waitUntilCleanOrFailed() {
        while (true) {
            synchronized (this) {
                queueDirty();
                if (pending.isEmpty()) {
                    boolean retryableDirty = books.entrySet().stream().anyMatch(entry -> {
                        UUID owner = entry.getKey();
                        long current = entry.getValue().revision();
                        return !writeDisabledOwners.containsKey(owner)
                                && current != savedRevision.getOrDefault(owner, 0L)
                                && failedRevision.getOrDefault(owner, Long.MIN_VALUE) != current;
                    });
                    if (!retryableDirty) return;
                }
                try {
                    wait(50L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** Explicitly permits an unsupported or oversized file to be replaced with this in-memory book. */
    public synchronized boolean repairOwner(UUID owner) {
        Objects.requireNonNull(owner);
        book(owner);
        if (writeDisabledOwners.remove(owner) == null) return false;
        savedRevision.put(owner, -1L);
        failedRevision.remove(owner);
        failures.remove(owner);
        return true;
    }

    synchronized boolean dirty(UUID owner) {
        TradeBook book = books.get(owner);
        return book != null && book.revision() != savedRevision.getOrDefault(owner, 0L);
    }

    synchronized boolean writesDisabled(UUID owner) {
        return writeDisabledOwners.containsKey(owner);
    }

    synchronized String failure(UUID owner) {
        return failures.get(owner);
    }

    private void fail(UUID owner, long revision, String message) {
        String previous = failures.get(owner);
        failedRevision.put(owner, revision);
        failures.put(owner, message);
        if (Objects.equals(previous, message)) return;
        LOGGER.warn("Trade data for owner {} is not saved: {}", owner, message);
    }

    private boolean backupCorrupt(UUID owner, Path file, Throwable cause) {
        Path backup = file.resolveSibling(file.getFileName() + ".bak");
        try {
            copier.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            disableWrites(owner, "could not back up corrupt trade file; original preserved until explicit repair");
            LOGGER.warn("Could not back up broken trade file {}; keeping owner read-only", file, e);
            LOGGER.warn("Trade file {} is invalid ({}); original bytes remain at {}", file, safeMessage(cause), file);
            return false;
        }
        LOGGER.warn(
                "Trade file {} is invalid ({}); using an empty book and preserving the original at {}",
                file,
                safeMessage(cause),
                backup);
        return true;
    }

    private void disableWrites(UUID owner, String reason) {
        String previous = writeDisabledOwners.put(owner, reason);
        if (!Objects.equals(previous, reason)) {
            LOGGER.warn("Trade data for owner {} is read-only: {}", owner, reason);
        }
    }

    private static String safeMessage(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    private Path file(UUID owner) {
        return dir.resolve(owner + ".json");
    }

    private static byte[] readBounded(Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file);
                var output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8 * 1024];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (read > TradeFile.MAX_FILE_BYTES - total) {
                    throw new IllegalArgumentException("trade file exceeds 8 MiB");
                }
                output.write(buffer, 0, read);
                total += read;
            }
            return output.toByteArray();
        }
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
        }
        flushAll();
        if (ownedWriter == null) return;
        ownedWriter.shutdown();
        try {
            if (!ownedWriter.awaitTermination(30, TimeUnit.SECONDS)) {
                LOGGER.warn("Trade writer did not stop within 30 seconds");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
