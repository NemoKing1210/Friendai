package dev.friendai.infrastructure.persistence.autonomy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.agent.autonomy.FreeTime;
import dev.friendai.agent.autonomy.FreeTimeService;
import dev.friendai.infrastructure.persistence.OwnerStoreWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FreeTimeStoreTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-00000000000a");

    @TempDir
    Path dir;

    @Test
    void missingRecordIsLazyAndSaveRoundTripsWithRevision() throws Exception {
        try (var writer = new OwnerStoreWriter();
                var store = new FreeTimeStore(dir, writer)) {
            assertEquals(FreeTime.OFF, store.state(OWNER));
            assertFalse(Files.exists(dir.resolve(OWNER + ".json")));
            new FreeTimeService(store).start(OWNER, FreeTime.Origin.IDLE, "food", 100);
            assertEquals(1, store.status(OWNER).currentRevision());
        }
        try (var writer = new OwnerStoreWriter();
                var reopened = new FreeTimeStore(dir, writer)) {
            assertEquals("food", reopened.state(OWNER).wish());
            assertTrue(reopened.state(OWNER).on());
        }
    }
}
