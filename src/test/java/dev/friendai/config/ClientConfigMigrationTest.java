package dev.friendai.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClientConfigMigrationTest {
    @TempDir
    Path dir;

    private ClientConfig load(String old) throws IOException {
        Path file = dir.resolve("friendai-client.json");
        Files.writeString(file, old);
        ConfigStore<ClientConfig> store = new ConfigStore<>(
                file, ClientConfig.class, ClientConfig::defaults, ClientConfig.CURRENT_VERSION, ClientConfig::migrate);
        return store.load();
    }

    @Test
    void migratesMainV1() throws IOException {
        String old = """
            {"version":1,"voice":{"enabled":false,"whisperModel":"small",
             "language":"ru","inputDevice":"Mic","maxRecordSec":10}}""";
        ClientConfig c = load(old);
        assertFalse(c.voice.stt.enabled);
        assertEquals("small", c.voice.stt.local.model);
        assertEquals("ru", c.voice.stt.language);
        assertEquals("Mic", c.voice.stt.inputDevice);
        assertEquals(10, c.voice.stt.maxRecordSec);
        assertFalse(c.voice.tts.enabled);
        assertEquals(4, JsonParser.parseString(Files.readString(dir.resolve("friendai-client.json")))
                .getAsJsonObject().get("version").getAsInt());
        assertFalse(c.debug.movementOverlay);

    }

    @Test
    void migratesCompanionSpeechV2() throws IOException {
        String old = """
            {"version":2,"voice":{"enabled":true,"whisperModel":"small","language":"ru",
             "inputDevice":"Mic","maxRecordSec":10,"tts":{"enabled":true,"provider":"openai",
             "volume":0.5,"maxChars":250,"speakGuests":true,"outputDevice":"Speakers",
             "local":{"voice":"ru_RU-dmitri-medium","speed":1.2},
             "openai":{"apiKey":"sk-o","baseUrl":"http://localhost:8080/v1","model":"m","voice":"nova","speed":1.5},
             "elevenlabs":{"apiKey":"sk-e","model":"em","voice":"v1","speed":1.1}}}}""";
        ClientConfig c = load(old);
        assertEquals("small", c.voice.stt.local.model);
        assertTrue(c.voice.tts.enabled);
        assertEquals("openai", c.voice.tts.provider);
        assertEquals(0.5, c.voice.tts.volume);
        assertEquals(250, c.voice.tts.maxChars);
        assertEquals("Speakers", c.voice.tts.outputDevice);
        assertEquals("ru_RU-dmitri-medium", c.voice.tts.local.voice);
        assertEquals("sk-o", c.voice.tts.cloud.get("openai").apiKey);
        assertEquals("http://localhost:8080/v1", c.voice.tts.cloud.get("openai").baseUrl);
        assertEquals(1.5, c.voice.tts.cloud.get("openai").speed);
        assertEquals("sk-e", c.voice.tts.cloud.get("elevenlabs").apiKey);
        assertEquals(1.1, c.voice.tts.cloud.get("elevenlabs").speed);
    }

    @Test
    void migratesVoiceServicesV2AndStripsVoicePrefix() throws IOException {
        String old = """
            {"version":2,"voice":{"stt":{"enabled":true,"provider":"groq","language":"ru",
             "maxRecordSec":45,"local":{"model":"small"},
             "cloud":{"groq":{"apiKey":"gsk","model":"whisper-large-v3"}}},
             "tts":{"enabled":true,"provider":"yandex","volume":0.7,"maxChars":200,
             "local":{"voice":"vits-piper-ru_RU-dmitri-medium"},
             "cloud":{"yandex":{"apiKey":"ya","voice":"filipp","folderId":"f1"}}}}}""";
        ClientConfig c = load(old);
        assertEquals("ru_RU-dmitri-medium", c.voice.tts.local.voice);
        assertEquals("yandex", c.voice.tts.provider);
        assertEquals("f1", c.voice.tts.cloud.get("yandex").folderId);
        assertEquals("groq", c.voice.stt.provider);
        assertEquals(45, c.voice.stt.maxRecordSec);
        assertEquals(1.0, c.voice.tts.local.speed);
    }

    @Test
    void librittsrVoiceSurvivesThePrefixStripAndTheCatalogLookup() throws IOException {
        String old = """
            {"version":2,"voice":{"stt":{"provider":"local","local":{"model":"base"}},
             "tts":{"provider":"local","local":{"voice":"vits-piper-en_US-libritts_r-medium"}}}}""";
        ClientConfig c = load(old);
        assertEquals("en_US-libritts_r-medium", c.voice.tts.local.voice);
        c.validate();
        assertEquals("en_US-libritts_r-medium", c.voice.tts.local.voice);
    }

    @Test
    void migrationKeepsUnknownKeys() {
        String old = """
            {"version":2,"voice":{"enabled":true,"whisperModel":"tiny","customVoiceFlag":true,
             "tts":{"enabled":false,"provider":"local","extra":7,
             "local":{"voice":"vits-piper-ru_RU-irina-medium","speed":1.0}}}}""";
        JsonObject migrated = ClientConfig.migrate(JsonParser.parseString(old).getAsJsonObject(), 2);
        JsonObject voice = migrated.getAsJsonObject("voice");
        JsonObject tts = voice.getAsJsonObject("tts");
        assertTrue(voice.get("customVoiceFlag").getAsBoolean());
        assertEquals(7, tts.get("extra").getAsInt());
        assertEquals(
                "ru_RU-irina-medium", tts.getAsJsonObject("local").get("voice").getAsString());
        assertFalse(voice.has("whisperModel"));
        assertEquals("auto", tts.get("language").getAsString());
    }

    @Test
    void version3AddsDisabledMovementOverlayAndPreservesVoice() {
        JsonObject json = JsonParser.parseString("""
            {"version":3,"voice":{"stt":{"provider":"openai"},"tts":{"provider":"yandex"}}}""")
                .getAsJsonObject();
        JsonObject migrated = ClientConfig.migrate(json, 3);
        assertEquals("openai", migrated.getAsJsonObject("voice").getAsJsonObject("stt").get("provider").getAsString());
        assertFalse(migrated.getAsJsonObject("debug").get("movementOverlay").getAsBoolean());
        assertEquals(4, migrated.get("version").getAsInt());
    }

    @Test
    void movementOverlayPreferenceSurvivesConfigSaveAndReload() {
        ClientConfig config = ClientConfig.defaults();
        config.debug.movementOverlay = true;
        Path file = dir.resolve("friendai-client.json");
        ConfigStore<ClientConfig> store = new ConfigStore<>(file, ClientConfig.class, ClientConfig::defaults,
                ClientConfig.CURRENT_VERSION, ClientConfig::migrate);
        store.save(config);
        assertTrue(store.load().debug.movementOverlay);

    }
}
