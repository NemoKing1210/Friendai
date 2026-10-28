package dev.friendai.placed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.pathing.Pos;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlacedStoreTest {
    private static final String OVERWORLD = "minecraft:overworld";

    @TempDir Path temp;

    private static PlacedBlock block(int x, String id) {
        return new PlacedBlock(OVERWORLD, new Pos(x, 64, 0), id, 1, "place_block");
    }

    @Test
    void missingFileWritesNothing() {
        Path dir = temp.resolve("placed");
        try (PlacedStore store = new PlacedStore(dir, PlacedLimits.DEFAULTS)) {
            assertTrue(store.book(UUID.randomUUID()).all().isEmpty());
        }
        assertFalse(Files.exists(dir));
    }

    @Test
    void corruptFileIsBackedUpAndTheBookStartsEmpty() throws IOException {
        Path dir = temp.resolve("placed");
        Files.createDirectories(dir);
        UUID owner = UUID.randomUUID();
        Files.writeString(dir.resolve(owner + ".json"), "{\"version\":0,\"entries\":[]}");
        try (PlacedStore store = new PlacedStore(dir, PlacedLimits.DEFAULTS)) {
            assertTrue(store.book(owner).all().isEmpty());
        }
        assertTrue(Files.exists(dir.resolve(owner + ".json.bak")));
    }

    @Test
    void entriesSurviveARestart() {
        Path dir = temp.resolve("placed");
        UUID owner = UUID.randomUUID();
        try (PlacedStore store = new PlacedStore(dir, PlacedLimits.DEFAULTS)) {
            store.book(owner).record(block(3, "minecraft:oak_log"));
            store.flushAll();
        }
        try (PlacedStore store = new PlacedStore(dir, PlacedLimits.DEFAULTS)) {
            PlacedBlock restored = store.book(owner).all().getFirst();
            assertEquals(new Pos(3, 64, 0), restored.pos());
            assertEquals("minecraft:oak_log", restored.blockId());
        }
    }

    @Test
    void flushWaitsForTheDebounceInterval() {
        ManualExecutor executor = new ManualExecutor();
        UUID owner = UUID.randomUUID();
        try (PlacedStore store = new PlacedStore(temp.resolve("placed"), PlacedLimits.DEFAULTS, executor, () -> 0L)) {
            store.book(owner).record(block(1, "minecraft:oak_log"));
            store.flushIfDue(PlacedStore.FLUSH_INTERVAL_MS - 1);
            assertEquals(0, executor.pending());
            assertTrue(store.dirty(owner));
            store.flushIfDue(PlacedStore.FLUSH_INTERVAL_MS);
            executor.runNext();
            assertFalse(store.dirty(owner));
        }
    }

    @Test
    void ownerFilesAreIsolated() {
        Path dir = temp.resolve("placed");
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        try (PlacedStore store = new PlacedStore(dir, PlacedLimits.DEFAULTS)) {
            store.book(first).record(block(1, "minecraft:oak_log"));
            assertTrue(store.book(second).all().isEmpty());
        }
    }

    private static final class ManualExecutor implements Executor {
        private final Queue<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        int pending() {
            return tasks.size();
        }

        void runNext() {
            tasks.remove().run();
        }
    }
}
