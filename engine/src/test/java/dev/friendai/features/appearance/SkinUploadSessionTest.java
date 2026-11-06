package dev.friendai.features.appearance;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class SkinUploadSessionTest {
    @Test
    void onlyTheSessionThatStartedTheOperationMayUpload() {
        UUID started = UUID.randomUUID();
        assertTrue(SkinUploadSession.allows(started, started, true), "the same session uploads");
        assertFalse(
                SkinUploadSession.allows(started, UUID.randomUUID(), true),
                "a reconnect must reject the old operation");
        assertFalse(SkinUploadSession.allows(null, UUID.randomUUID(), true), "no operation, nothing to send");
        assertFalse(SkinUploadSession.allows(started, started, false), "a peer without appearance support refuses");
    }
}
