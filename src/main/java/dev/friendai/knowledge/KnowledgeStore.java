package dev.friendai.knowledge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.io.UncheckedIOException;
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

/** Per-owner knowledge cache with bounded, serialized snapshots and atomic file replacement. */
public final class KnowledgeStore implements AutoCloseable {
    public static final long FLUSH_INTERVAL_MS = 5_000;
    public static final long MAX_FILE_BYTES = 8L * 1024 * 1024;
    private static final Logger LOGGER = LoggerFactory.getLogger("friendai/knowledge");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().serializeNulls().create();

    private final Path dir;
    private final KnowledgeLimits limits;
    private final Executor writer;
    private final ExecutorService ownedWriter;
    private final boolean waitForWrites;
    private final LongSupplier clock;
    private final Map<UUID, ResourceKnowledge> books = new HashMap<>();
    private final Map<UUID, Long> savedRevision = new HashMap<>();
    private final Map<UUID, Long> failedRevision = new HashMap<>();
    private final Set<UUID> pending = new HashSet<>();
    private long lastFlush;

    public KnowledgeStore(Path dir, KnowledgeLimits limits) {
        this(dir, limits, newWriter(), true, System::currentTimeMillis);
    }

    KnowledgeStore(Path dir, KnowledgeLimits limits, Executor writer, LongSupplier clock) {
        this(dir, limits, writer, null, false, clock);
    }

    private KnowledgeStore(Path dir, KnowledgeLimits limits, ExecutorService writer, boolean waitForWrites,
                           LongSupplier clock) {
        this(dir, limits, (Executor) writer, writer, waitForWrites, clock);
    }

    private KnowledgeStore(Path dir, KnowledgeLimits limits, Executor writer, ExecutorService ownedWriter,
                           boolean waitForWrites, LongSupplier clock) {
        this.dir = dir;
        this.limits = limits;
        this.writer = writer;
        this.ownedWriter = ownedWriter;
        this.waitForWrites = waitForWrites;
        this.clock = clock;
    }

    private static ExecutorService newWriter() {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "friendai-knowledge-writer");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Loads lazily; a missing file creates an empty in-memory book without touching disk. */
    public synchronized ResourceKnowledge book(UUID owner) {
        ResourceKnowledge loaded = books.get(owner);
        if (loaded != null) return loaded;
        Path file = file(owner);
        if (!Files.exists(file)) {
            ResourceKnowledge empty = new ResourceKnowledge(limits);
            books.put(owner, empty);
            savedRevision.put(owner, 0L);
            return empty;
        }
        try {
            long size = Files.size(file);
            if (size > MAX_FILE_BYTES) throw new IllegalArgumentException("knowledge file exceeds 8 MiB");
            String json = Files.readString(file, StandardCharsets.UTF_8);
            KnowledgeFile parsed = GSON.fromJson(json, KnowledgeFile.class);
            if (parsed == null) throw new IllegalArgumentException("empty knowledge file");
            ResourceKnowledge book = ResourceKnowledge.restore(parsed, limits);
            books.put(owner, book);
            savedRevision.put(owner, book.revision());
            return book;
        } catch (IOException | RuntimeException | StackOverflowError e) {
            backupCorrupt(file, e);
            ResourceKnowledge empty = new ResourceKnowledge(limits);
            books.put(owner, empty);
            savedRevision.put(owner, 0L);
            return empty;
        }
    }

    /** Flushes dirty books after the debounce interval; only one immutable snapshot per owner may be queued. */
    public synchronized void flushIfDue(long nowMillis) {
        long now = Math.max(lastFlush, nowMillis);
        if (now - lastFlush < FLUSH_INTERVAL_MS) return;
        lastFlush = now;
        failedRevision.clear();
        queueDirty();
    }

    public void flushIfDue() { flushIfDue(clock.getAsLong()); }

    /** Schedules the latest snapshot of every dirty owner. Production stores wait through pending revisions. */
    public void flushAll() {
        synchronized (this) {
            failedRevision.clear();
            queueDirty();
        }
        if (waitForWrites) waitUntilCleanOrFailed();
    }

    private synchronized void queueDirty() {
        for (Map.Entry<UUID, ResourceKnowledge> entry : books.entrySet()) {
            UUID owner = entry.getKey();
            ResourceKnowledge book = entry.getValue();
            long revision = book.revision();
            if (revision == savedRevision.getOrDefault(owner, 0L) || pending.contains(owner)
                    || failedRevision.getOrDefault(owner, Long.MIN_VALUE) == revision) continue;
            String json = GSON.toJson(book.snapshot());
            pending.add(owner);
            try {
                writer.execute(() -> save(owner, revision, json));
            } catch (RuntimeException e) {
                pending.remove(owner);
                failedRevision.put(owner, revision);
                LOGGER.warn("Could not queue knowledge file for owner {}", owner, e);
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
            LOGGER.warn("Could not save knowledge file for owner {}", owner, e);
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
                    boolean dirtyCanRetry = books.entrySet().stream().anyMatch(entry -> {
                        long current = entry.getValue().revision();
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

    synchronized boolean dirty(UUID owner) {
        ResourceKnowledge book = books.get(owner);
        return book != null && book.revision() != savedRevision.getOrDefault(owner, 0L);
    }

    private Path file(UUID owner) { return dir.resolve(owner + ".json"); }

    private void backupCorrupt(Path file, Throwable cause) {
        Path backup = file.resolveSibling(file.getFileName() + ".bak");
        try {
            Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LOGGER.warn("Could not back up broken knowledge file {}", file, e);
        }
        LOGGER.warn("Knowledge file {} is invalid ({}); using an empty atlas and keeping the original at {}",
                file, cause.getMessage(), backup);
    }

    @Override
    public void close() {
        flushAll();
        if (ownedWriter == null) return;
        ownedWriter.shutdown();
        try {
            if (!ownedWriter.awaitTermination(30, TimeUnit.SECONDS)) {
                LOGGER.warn("Knowledge writer did not stop within 30 seconds");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
