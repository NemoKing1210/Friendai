package dev.friendai.presentation.config;

import static org.junit.jupiter.api.Assertions.*;

import dev.friendai.infrastructure.config.ClientConfig;
import dev.friendai.infrastructure.config.ServerConfig;
import dev.friendai.infrastructure.config.VoiceDraft;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SettingsSessionTest {
    @Test
    void rebuildsShareOneDetachedDraft() {
        ClientConfig live = ClientConfig.defaults();
        live.voice.stt.maxRecordSec = 30;
        ServerConfig server = ServerConfig.defaults();
        try (var session = SettingsSession.open(1, live, server, false)) {
            session.serverDraft().companion.name = "Edited name";
            session.clientDraft().voice.stt.maxRecordSec = 60;

            assertEquals(30, live.voice.stt.maxRecordSec, "the live client config stays untouched before Save");
            assertEquals("Friendai", ServerConfig.defaults().companion.name);
            assertEquals(
                    "Edited name", session.serverDraft().companion.name, "the same server draft survives a rebuild");
            assertEquals(60, session.clientDraft().voice.stt.maxRecordSec, "the same client draft survives a rebuild");
        }
    }

    @Test
    void savePublishesBothDraftsOnceAndCloses() {
        AtomicInteger clients = new AtomicInteger();
        AtomicInteger servers = new AtomicInteger();
        try (var session = SettingsSession.open(1, ClientConfig.defaults(), ServerConfig.defaults(), false)) {
            session.serverDraft().companion.name = "Saved";
            session.clientDraft().voice.stt.maxRecordSec = 999;
            session.publish(config -> clients.incrementAndGet(), config -> servers.incrementAndGet());

            assertEquals(1, clients.get(), "the client draft is published once");
            assertEquals(1, servers.get(), "the server draft is published once");
            assertFalse(session.isOpen());
            assertThrows(
                    IllegalStateException.class,
                    () -> session.publish(config -> clients.incrementAndGet(), config -> servers.incrementAndGet()),
                    "a closed session cannot publish a second time");
            assertEquals(1, clients.get());
        }
    }

    @Test
    void saveClampsAndFoldsTheVoiceDraftIntoTheClientDraft() {
        AtomicReference<ClientConfig> published = new AtomicReference<>();
        try (var session = SettingsSession.open(1, ClientConfig.defaults(), null, false)) {
            session.clientDraft().voice.stt.maxRecordSec = 999;
            VoiceDraft voice = new VoiceDraft(
                    "openai",
                    new VoiceDraft.Cloud("key", "whisper-1", null, null, null, "en"),
                    false,
                    "openai",
                    new VoiceDraft.Cloud("tts-key", "tts-1", "alloy", null, null, "en"),
                    false);
            session.voiceDraft(voice);
            session.publish(published::set, ignored -> fail("this connection cannot edit server settings"));

            assertEquals(120, published.get().voice.stt.maxRecordSec, "the draft is validated before publication");
            assertEquals(
                    "whisper-1", published.get().voice.stt.cloud.get("openai").model, "the voice draft reaches disk");
        }
    }

    @Test
    void canceledSessionPublishesNothing() {
        AtomicInteger published = new AtomicInteger();
        var session = SettingsSession.open(1, ClientConfig.defaults(), ServerConfig.defaults(), false);
        session.clientDraft().voice.stt.maxRecordSec = 44;
        session.serverDraft().companion.name = "Discarded";
        session.close();

        assertFalse(session.isOpen());
        assertEquals(0, published.get());
        assertThrows(
                IllegalStateException.class,
                () -> session.publish(ignored -> published.incrementAndGet(), ignored -> published.incrementAndGet()));
        assertEquals(0, published.get(), "Cancel must never publish");
    }

    @Test
    void aStaleGenerationCannotDriveTheNextScreen() {
        var first = SettingsSession.open(1, ClientConfig.defaults(), ServerConfig.defaults(), false);
        var second = SettingsSession.open(2, ClientConfig.defaults(), ServerConfig.defaults(), false);

        assertTrue(first.current(1));
        assertFalse(first.current(2), "a callback from the previous screen is stale");
        assertTrue(second.current(2));
        assertFalse(second.current(1), "a callback from the next screen cannot touch the previous one");
        first.close();
        assertFalse(first.current(1), "a closed session rejects its own generation");
    }

    @Test
    void unsupportedServerSettingsStayAbsentAndReadOnly() {
        try (var session = SettingsSession.open(1, ClientConfig.defaults(), ServerConfig.defaults(), true)) {
            assertNull(session.serverDraft());
            assertTrue(session.serverReadOnly());
            session.publish(ignored -> {}, ignored -> fail("an unsupported file must not be written"));
        }
    }
}
