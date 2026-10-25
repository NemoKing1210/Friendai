package dev.friendai.memory;

import dev.friendai.config.ConfigStore;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads, caches and persists one {@link MemoryBook} per owner. Dirty books are written at most once per
 * {@link #FLUSH_INTERVAL_MS}, and all of them on shutdown. Must be called on the server thread.
 */
public final class MemoryStore {
    public static final long FLUSH_INTERVAL_MS = 5_000;
    private static final Logger LOGGER = LoggerFactory.getLogger("friendai/memory");

    private record Loaded(MemoryBook book, ConfigStore<MemoryFile> store) {}

    private final Path dir;
    private final LongSupplier clock;
    private final Map<UUID, Loaded> books = new HashMap<>();
    private final Set<UUID> corruptionNotices = new HashSet<>();
    private long lastFlush;

    public MemoryStore(Path dir, LongSupplier clock) {
        this.dir = dir;
        this.clock = clock;
    }

    /** The owner's book, loaded from disk on first use. A missing file gives an empty book and writes nothing. */
    public synchronized MemoryBook book(UUID owner) {
        Loaded loaded = books.get(owner);
        if (loaded != null) {
            return loaded.book();
        }
        Path file = dir.resolve(owner + ".json");
        ConfigStore<MemoryFile> store =
                new ConfigStore<>(file, MemoryFile.class, MemoryFile::new, 2, (json, from) -> json);
        MemoryBook book;
        if (Files.exists(file)) {
            book = MemoryBook.fromFile(store.load());
            if (store.recoveredOnLastLoad()) {
                corruptionNotices.add(owner);
            }
        } else {
            book = new MemoryBook();
        }
        Loaded created = new Loaded(book, store);
        books.put(owner, created);
        return book;
    }

    /** True once after that owner's book was recovered from a corrupt file. */
    public synchronized boolean takeCorruptionNotice(UUID owner) {
        return corruptionNotices.remove(owner);
    }

    /** Saves every dirty book if at least {@link #FLUSH_INTERVAL_MS} passed since the last flush. */
    public synchronized void flushIfDue() {
        long now = clock.getAsLong();
        if (now - lastFlush < FLUSH_INTERVAL_MS) {
            return;
        }
        lastFlush = now;
        saveDirty();
    }

    /** Saves every dirty book now. */
    public synchronized void flushAll() {
        saveDirty();
    }

    private void saveDirty() {
        for (Loaded loaded : books.values()) {
            if (!loaded.book().isDirty()) {
                continue;
            }
            try {
                loaded.store().save(loaded.book().toFile());
                loaded.book().markClean();
            } catch (UncheckedIOException e) {
                LOGGER.warn("Could not save memory file {}", loaded.store().file(), e);
            }
        }
    }

}
