package dev.friendai.infrastructure.persistence.routines;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import dev.friendai.agent.routine.Routine;
import dev.friendai.agent.routine.Routine.IntervalTrigger;
import dev.friendai.agent.routine.Routine.Step;
import dev.friendai.infrastructure.persistence.OwnerStoreWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RoutineStoreTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-00000000001a");

    @TempDir
    Path dir;

    @Test
    void missingRecordIsLazyAndSaveRoundTripsWithRevision() throws Exception {
        Routine routine = Routine.create(
                "morning", new IntervalTrigger(30), List.of(new Step("mine_blocks", new JsonObject())), 3, 100, 0);
        try (var writer = new OwnerStoreWriter();
                var store = new RoutineStore(dir, writer)) {
            assertTrue(store.state(OWNER).isEmpty());
            assertFalse(Files.exists(dir.resolve(OWNER + ".json")));
            store.save(OWNER, routine);
            assertEquals(1, store.status(OWNER).currentRevision());
        }
        try (var writer = new OwnerStoreWriter();
                var reopened = new RoutineStore(dir, writer)) {
            assertEquals(routine, reopened.state(OWNER).orElseThrow());
        }
    }

    @Test
    void clearRemovesTheRoutineAndRevisionAdvances() throws Exception {
        try (var writer = new OwnerStoreWriter();
                var store = new RoutineStore(dir, writer)) {
            store.save(
                    OWNER,
                    Routine.create(
                            "morning",
                            new IntervalTrigger(30),
                            List.of(new Step("mine_blocks", new JsonObject())),
                            0,
                            100,
                            0));
            store.clear(OWNER);
            assertTrue(store.state(OWNER).isEmpty());
            assertEquals(2, store.status(OWNER).currentRevision());
        }
    }

    @Test
    void anOversizedFileReadsAsNoRoutine() throws Exception {
        Files.createDirectories(dir);
        Path file = dir.resolve(OWNER + ".json");
        Files.writeString(file, "x".repeat(40 * 1024));
        try (var writer = new OwnerStoreWriter();
                var store = new RoutineStore(dir, writer)) {
            assertTrue(store.state(OWNER).isEmpty());
        }
    }

    @Test
    void aCorruptFileIsNotOverwrittenByAFailedSave() throws Exception {
        Files.createDirectories(dir);
        Path file = dir.resolve(OWNER + ".json");
        Files.writeString(file, "{not json");
        try (var writer = new OwnerStoreWriter();
                var store = new RoutineStore(dir, writer)) {
            assertTrue(store.state(OWNER).isEmpty());
            store.save(
                    OWNER,
                    Routine.create(
                            "morning",
                            new IntervalTrigger(30),
                            List.of(new Step("mine_blocks", new JsonObject())),
                            0,
                            100,
                            0));
            store.flushAll();
            assertEquals("{not json", Files.readString(file), "the unreadable original stays untouched");
        }
    }

    @Test
    void corruptAndUnsupportedFilesReadAsNoRoutine() throws Exception {
        Files.createDirectories(dir);
        Path file = dir.resolve(OWNER + ".json");
        Files.writeString(file, "{\"formatVersion\":1,\"revision\":1,\"payload\":\"{broken\"}");
        try (var writer = new OwnerStoreWriter();
                var store = new RoutineStore(dir, writer)) {
            assertTrue(store.state(OWNER).isEmpty());
        }

        Files.writeString(file, "{\"formatVersion\":99,\"revision\":1,\"payload\":null}");
        try (var writer = new OwnerStoreWriter();
                var store = new RoutineStore(dir, writer)) {
            assertTrue(store.state(OWNER).isEmpty(), "an unsupported version is not interpreted");
        }
    }
}
