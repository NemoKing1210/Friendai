package dev.friendai.infrastructure.persistence.goals;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.agent.goal.GoalBoard;
import dev.friendai.foundation.persistence.StoreReadOnlyReason;
import dev.friendai.infrastructure.persistence.OwnerStoreWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GoalStoreTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-00000000000a");

    @TempDir
    Path dir;

    @Test
    void missingRecordIsLazyAndSaveRoundTripsWithRevision() throws Exception {
        try (var writer = new OwnerStoreWriter();
                var store = new GoalStore(dir, writer)) {
            assertTrue(store.board(OWNER, 3, 2).all().isEmpty());
            assertFalse(Files.exists(dir.resolve(OWNER + ".json")));
            GoalBoard board = store.board(OWNER, 3, 2);
            board.set("gather oak", List.of(), false, 10);
            store.save(OWNER, board);
            assertEquals(1, store.status(OWNER).currentRevision());
        }
        try (var writer = new OwnerStoreWriter();
                var reopened = new GoalStore(dir, writer)) {
            assertEquals(
                    "gather oak",
                    reopened.board(OWNER, 3, 2).primary().orElseThrow().text());
        }
    }

    @Test
    void structurallyInvalidPayloadIsBackedUpAndCannotOverwriteOriginal() throws Exception {
        Files.createDirectories(dir);
        Path original = dir.resolve(OWNER + ".json");
        String invalid = "{\"formatVersion\":1,\"revision\":7,\"payload\":\"{}\"}";
        Files.writeString(original, invalid);

        try (var writer = new OwnerStoreWriter();
                var store = new GoalStore(dir, writer)) {
            assertTrue(store.board(OWNER, 3, 2).all().isEmpty());
            assertEquals(StoreReadOnlyReason.CORRUPT, store.status(OWNER).readOnlyReason());
            assertEquals(invalid, Files.readString(original));
            assertEquals(invalid, Files.readString(original.resolveSibling(original.getFileName() + ".bak")));

            GoalBoard recovery = store.board(OWNER, 3, 2);
            recovery.set("replacement", List.of(), false, 11);
            store.save(OWNER, recovery);
            store.flushAll();
            assertEquals(invalid, Files.readString(original));
        }
    }
}
