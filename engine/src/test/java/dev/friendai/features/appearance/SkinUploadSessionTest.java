package dev.friendai.features.appearance;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.presentation.companion.ClientSessionId;
import org.junit.jupiter.api.Test;

class SkinUploadSessionTest {
    @Test
    void onlyTheSessionThatStartedTheOperationMayUpload() {
        ClientSessionId started = ClientSessionId.create();
        assertTrue(SkinUploadSession.allows(started, started, true), "the same session uploads");
        assertFalse(
                SkinUploadSession.allows(started, ClientSessionId.create(), true),
                "a reconnect must reject the old operation");
        assertFalse(SkinUploadSession.allows(null, ClientSessionId.create(), true), "no operation, nothing to send");
        assertFalse(SkinUploadSession.allows(started, started, false), "a peer without appearance support refuses");
    }
}
