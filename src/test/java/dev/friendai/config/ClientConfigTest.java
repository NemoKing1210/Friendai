package dev.friendai.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
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
                dir.resolve("friendai-client.json"), ClientConfig.class, ClientConfig::defaults, 2, ClientConfig::migrate);
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

    @Test
    void ttsDefaultsAreOffAndLocal() {
        ClientConfig c = ClientConfig.defaults();
        assertFalse(c.voice.tts.enabled);
        assertEquals("local", c.voice.tts.provider);
        assertEquals(1.0, c.voice.tts.volume);
        assertEquals(300, c.voice.tts.maxChars);
        assertEquals(1.0, c.voice.tts.local.speed);
    }

    @Test
    void v1ConfigMigratesToV2WithTtsDefaults() throws IOException {
        Path file = dir.resolve("friendai-client.json");
        Files.writeString(file, """
            {"version":1,"voice":{"enabled":false,"whisperModel":"small","language":"ru","inputDevice":"Mic","maxRecordSec":10}}
            """);
        ConfigStore<ClientConfig> store = new ConfigStore<>(
                file, ClientConfig.class, ClientConfig::defaults, 2, ClientConfig::migrate);
        ClientConfig c = store.load();
        assertFalse(c.voice.enabled);
        assertEquals("small", c.voice.whisperModel);
        assertFalse(c.voice.tts.enabled);
        assertEquals("local", c.voice.tts.provider);
        assertEquals(2, JsonParser.parseString(Files.readString(file)).getAsJsonObject().get("version").getAsInt());
    }

    @Test
    void validateClampsTtsValues() {
        ClientConfig c = ClientConfig.defaults();
        c.voice.tts.volume = 5;
        c.voice.tts.maxChars = 9999;
        c.voice.tts.local.speed = 9;
        c.voice.tts.provider = "bogus";
        c.validate();
        assertEquals(1.0, c.voice.tts.volume);
        assertEquals(500, c.voice.tts.maxChars);
        assertEquals(2.0, c.voice.tts.local.speed);
        assertEquals("local", c.voice.tts.provider);
    }
}
