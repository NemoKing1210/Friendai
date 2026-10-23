package dev.friendai.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClientConfigTest {
    @TempDir
    Path dir;

    @Test
    void defaultsMatchSpec() {
        ClientConfig c = ClientConfig.defaults();
        assertTrue(c.voice.enabled);
        assertEquals("base", c.voice.whisperModel);
        assertEquals("auto", c.voice.language);
        assertNull(c.voice.inputDevice);
        assertEquals(30, c.voice.maxRecordSec);
    }

    @Test
    void roundTripsThroughStore() {
        ConfigStore<ClientConfig> store = new ConfigStore<>(
                dir.resolve("friendai-client.json"), ClientConfig.class, ClientConfig::defaults, 1, (json, from) -> json);
        ClientConfig c = store.load();
        c.voice.whisperModel = "small";
        c.voice.inputDevice = "Mic 1";
        c.voice.enabled = false;
        store.save(c);
        ClientConfig again = store.load();
        assertEquals("small", again.voice.whisperModel);
        assertEquals("Mic 1", again.voice.inputDevice);
        assertTrue(!again.voice.enabled);
    }

    @Test
    void validateClampsRecordingLength() {
        ClientConfig c = ClientConfig.defaults();
        c.voice.maxRecordSec = 9999;
        c.validate();
        assertEquals(120, c.voice.maxRecordSec);
    }
}
