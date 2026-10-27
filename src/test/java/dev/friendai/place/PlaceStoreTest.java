package dev.friendai.place;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.pathing.Pos;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlaceStoreTest {
    private static final String OVERWORLD = "minecraft:overworld";

    @TempDir Path temp;

    @Test
    void corruptFileIsBackedUpAndTheBookStartsEmpty() throws IOException {
        Path dir = temp.resolve("places");
        Files.createDirectories(dir);
        UUID owner = UUID.randomUUID();
        Files.writeString(dir.resolve(owner + ".json"), "{\"version\":1,\"nextId\":0,\"places\":[]}");
        try (PlaceStore store = new PlaceStore(dir, PlaceLimits.DEFAULTS)) {
            assertTrue(store.book(owner).all().isEmpty());
        }
        assertTrue(Files.exists(dir.resolve(owner + ".json.bak")));
    }

    @Test
    void missingFileWritesNothing() {
        Path dir = temp.resolve("places");
        try (PlaceStore store = new PlaceStore(dir, PlaceLimits.DEFAULTS)) {
            assertTrue(store.book(UUID.randomUUID()).all().isEmpty());
        }
        assertFalse(Files.exists(dir));
    }

    @Test
    void placeWithAliasesSurvivesARestart() {
        Path dir = temp.resolve("places");
        UUID owner = UUID.randomUUID();
        try (PlaceStore store = new PlaceStore(dir, PlaceLimits.DEFAULTS)) {
            store.book(owner).remember(values("home", PlaceKind.HOUSE, new Pos(1, 64, 2), Set.of("дом")), 7);
            store.flushAll();
        }
        try (PlaceStore store = new PlaceStore(dir, PlaceLimits.DEFAULTS)) {
            Place copy = store.book(owner).find("дом", OVERWORLD).orElseThrow();
            assertEquals("home", copy.name());
            assertEquals(7, copy.createdTick());
        }
    }

    @Test
    void ownerFilesAreIsolated() {
        Path dir = temp.resolve("places");
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        try (PlaceStore store = new PlaceStore(dir, PlaceLimits.DEFAULTS)) {
            store.book(first).remember(values("home", PlaceKind.HOUSE, new Pos(0, 64, 0), Set.of()), 1);
            assertTrue(store.book(second).all().isEmpty());
        }
    }

    @Test
    void oversizedFileIsBackedUpAndTheBookStartsEmpty() throws IOException {
        Path dir = temp.resolve("places");
        Files.createDirectories(dir);
        UUID owner = UUID.randomUUID();
        PlaceLimits tiny = new PlaceLimits(8, 32, 4, 160, 64);
        Files.writeString(dir.resolve(owner + ".json"), "x".repeat(65));
        try (PlaceStore store = new PlaceStore(dir, tiny)) {
            assertTrue(store.book(owner).all().isEmpty());
        }
        assertTrue(Files.exists(dir.resolve(owner + ".json.bak")));
    }

    @Test
    void flushWaitsForTheDebounceInterval() {
        ManualExecutor executor = new ManualExecutor();
        UUID owner = UUID.randomUUID();
        try (PlaceStore store = new PlaceStore(temp.resolve("places"), PlaceLimits.DEFAULTS, executor, () -> 0L)) {
            store.book(owner).remember(values("home", PlaceKind.HOUSE, new Pos(0, 64, 0), Set.of()), 1);
            store.flushIfDue(PlaceStore.FLUSH_INTERVAL_MS - 1);
            assertEquals(0, executor.pending());
            assertTrue(store.dirty(owner));
            store.flushIfDue(PlaceStore.FLUSH_INTERVAL_MS);
            executor.runNext();
            assertFalse(store.dirty(owner));
        }
    }

    @Test
    void aRevisionChangedDuringTheSaveStaysDirty() {
        ManualExecutor executor = new ManualExecutor();
        UUID owner = UUID.randomUUID();
        try (PlaceStore store = new PlaceStore(temp.resolve("places"), PlaceLimits.DEFAULTS, executor, () -> 0L)) {
            Place home = store.book(owner).remember(values("home", PlaceKind.HOUSE, new Pos(0, 64, 0), Set.of()), 1);
            store.flushIfDue(PlaceStore.FLUSH_INTERVAL_MS);
            store.book(owner).visit(home.id(), 2);
            executor.runNext();
            assertTrue(store.dirty(owner));
            store.flushIfDue(PlaceStore.FLUSH_INTERVAL_MS * 2);
            executor.runNext();
            assertFalse(store.dirty(owner));
        }
    }

    private static PlaceValues values(String name, PlaceKind kind, Pos pos, Set<String> aliases) {
        return new PlaceValues(
                name, kind, "", OVERWORLD, pos, 8, aliases, PlaceSource.MANUAL, null, "");
    }

    private static final class ManualExecutor implements Executor {
        private final Queue<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        void runNext() {
            tasks.remove().run();
        }

        int pending() {
            return tasks.size();
        }
    }
}
