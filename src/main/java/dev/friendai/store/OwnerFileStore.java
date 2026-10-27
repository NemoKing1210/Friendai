package dev.friendai.store;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-owner JSON files with lazy loading, debounced background writes and atomic replacement.
 *
 * @param <T> value stored for one owner; mutations must raise {@link StoreCodec#revision(Object)}
 */
public final class OwnerFileStore<T> implements AutoCloseable {
    public static final long FLUSH_INTERVAL_MS = 5_000;
    private static final Logger LOGGER = LoggerFactory.getLogger("friendai/store");

    private final Path dir;
    private final StoreCodec<T> codec;
    private final Executor writer;
    private final ExecutorService ownedWriter;
    private final boolean waitForWrites;
    private final LongSupplier clock;
    private final Map<UUID, T> values = new HashMap<>();
    private final Map<UUID, Long> savedRevision = new HashMap<>();
    private final Map<UUID, Long> failedRevision = new HashMap<>();
    private final Set<UUID> pending = new HashSet<>();
    private long lastFlush;

    public OwnerFileStore(Path dir, StoreCodec<T> codec, Executor writer, LongSupplier clock, boolean waitForWrites) {
        this(dir, codec, writer, null, clock, waitForWrites);
    }

    private OwnerFileStore(Path dir, StoreCodec<T> codec, Executor writer, ExecutorService ownedWriter,
                           LongSupplier clock, boolean waitForWrites) {
        this.dir = dir;
        this.codec = codec;
        this.writer = writer;
        this.ownedWriter = ownedWriter;
        this.waitForWrites = waitForWrites;
        this.clock = clock;
    }

    /** Builds a store that owns a daemon writer thread and waits for writes when flushing. */
    public static <T> OwnerFileStore<T> withWriterThread(Path dir, StoreCodec<T> codec) {
        ExecutorService writer = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "friendai-store-writer");
            thread.setDaemon(true);
            return thread;
        });
        return new OwnerFileStore<>(dir, codec, writer, writer, System::currentTimeMillis, true);
    }

    /** Loads lazily; a missing file creates an empty value without touching disk. */
    public synchronized T book(UUID owner) {
        T loaded = values.get(owner);
        if (loaded != null) return loaded;
        Path file = file(owner);
        if (!Files.exists(file)) return adopt(owner, fresh(), 0L);
        try {
            long size = Files.size(file);
            if (size > codec.maxBytes()) {
                throw new IllegalArgumentException("owner file exceeds " + codec.maxBytes() + " bytes");
            }
            String json = Files.readString(file, StandardCharsets.UTF_8);
            T value = codec.load(json);
            if (value == null) throw new IllegalArgumentException("empty owner file");
            return adopt(owner, value, codec.revision(value));
        } catch (IOException | RuntimeException | StackOverflowError e) {
            backupCorrupt(file, e);
            return adopt(owner, fresh(), 0L);
        }
    }

    /** Flushes dirty values after the debounce interval; only one immutable snapshot per owner may be queued. */
    public synchronized void flushIfDue(long nowMillis) {
        long now = Math.max(lastFlush, nowMillis);
        if (now - lastFlush < FLUSH_INTERVAL_MS) return;
        lastFlush = now;
        failedRevision.clear();
        queueDirty();
    }

    public void flushIfDue() { flushIfDue(clock.getAsLong()); }

    /** Schedules the latest snapshot of every dirty owner. Stores waiting for writes block until settled. */
    public void flushAll() {
        synchronized (this) {
            failedRevision.clear();
            queueDirty();
        }
        if (waitForWrites) waitUntilCleanOrFailed();
    }

    /** True when the in-memory value has revisions the disk does not. */
    public synchronized boolean dirty(UUID owner) {
        T value = values.get(owner);
        return value != null && codec.revision(value) != savedRevision.getOrDefault(owner, 0L);
    }

    @Override
    public void close() {
        flushAll();
        if (ownedWriter == null) return;
        ownedWriter.shutdown();
        try {
            if (!ownedWriter.awaitTermination(30, TimeUnit.SECONDS)) {
                LOGGER.warn("Owner file writer did not stop within 30 seconds");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private T fresh() { return codec.load(null); }

    private T adopt(UUID owner, T value, long revision) {
        values.put(owner, value);
        savedRevision.put(owner, revision);
        return value;
    }

    private synchronized void queueDirty() {
        for (Map.Entry<UUID, T> entry : values.entrySet()) {
            UUID owner = entry.getKey();
            T value = entry.getValue();
            long revision = codec.revision(value);
            if (revision == savedRevision.getOrDefault(owner, 0L) || pending.contains(owner)
                    || failedRevision.getOrDefault(owner, Long.MIN_VALUE) == revision) continue;
            String json = codec.save(value);
            pending.add(owner);
            try {
                writer.execute(() -> save(owner, revision, json));
            } catch (RuntimeException e) {
                pending.remove(owner);
                failedRevision.put(owner, revision);
                LOGGER.warn("Could not queue owner file for {}", owner, e);
            }
        }
    }

    private void save(UUID owner, long revision, String json) {
        boolean saved = false;
        try {
            Path target = file(owner);
            Files.createDirectories(target.toAbsolutePath().getParent());
            Path temporary = Files.createTempFile(target.toAbsolutePath().getParent(), target.getFileName().toString(), ".tmp");
            try {
                Files.writeString(temporary, json, StandardCharsets.UTF_8);
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
            saved = true;
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Could not save owner file for {}", owner, e);
        } finally {
            synchronized (this) {
                pending.remove(owner);
                if (saved) savedRevision.put(owner, revision);
                else failedRevision.put(owner, revision);
                notifyAll();
            }
        }
    }

    private void waitUntilCleanOrFailed() {
        while (true) {
            synchronized (this) {
                queueDirty();
                if (pending.isEmpty()) {
                    boolean dirtyCanRetry = values.entrySet().stream().anyMatch(entry -> {
                        long current = codec.revision(entry.getValue());
                        return current != savedRevision.getOrDefault(entry.getKey(), 0L)
                                && failedRevision.getOrDefault(entry.getKey(), Long.MIN_VALUE) != current;
                    });
                    if (!dirtyCanRetry) return;
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

    private Path file(UUID owner) { return dir.resolve(owner + ".json"); }

    private void backupCorrupt(Path file, Throwable cause) {
        Path backup = file.resolveSibling(file.getFileName() + ".bak");
        try {
            Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LOGGER.warn("Could not back up broken owner file {}", file, e);
        }
        LOGGER.warn("Owner file {} is invalid ({}); starting empty and keeping the original at {}",
                file, cause.getMessage(), backup);
    }
}
