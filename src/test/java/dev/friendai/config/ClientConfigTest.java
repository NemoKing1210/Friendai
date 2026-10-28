package dev.friendai.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.infrastructure.config.ConfigStore;
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
        assertTrue(c.voice.stt.enabled);
        assertEquals("local", c.voice.stt.provider);
        assertEquals("base", c.voice.stt.local.model);
        assertEquals("auto", c.voice.stt.language);
        assertNull(c.voice.stt.inputDevice);
        assertEquals(30, c.voice.stt.maxRecordSec);
        assertFalse(c.voice.tts.enabled);
        assertEquals("local", c.voice.tts.provider);
        assertEquals("auto", c.voice.tts.language);
        assertEquals(1.0, c.voice.tts.volume);
        assertEquals(300, c.voice.tts.maxChars);
        assertTrue(c.voice.tts.spatial);
        assertFalse(c.voice.tts.speakGuests);
        assertNull(c.voice.tts.outputDevice);
        assertNull(c.voice.tts.local.voice);
        assertEquals(1.0, c.voice.tts.local.speed);
    }

    @Test
    void roundTripsThroughStore() {
        ConfigStore<ClientConfig> store = new ConfigStore<>(
                dir.resolve("friendai-client.json"),
                ClientConfig.class,
                ClientConfig::defaults,
                ClientConfig.CURRENT_VERSION,
                ClientConfig::migrate);
        ClientConfig c = store.load();
        c.voice.stt.local.model = "small";
        c.voice.stt.inputDevice = "Mic 1";
        c.voice.stt.enabled = false;
        c.voice.tts.volume = 0.5;
        store.save(c);
        ClientConfig again = store.load();
        assertEquals("small", again.voice.stt.local.model);
        assertEquals("Mic 1", again.voice.stt.inputDevice);
        assertFalse(again.voice.stt.enabled);
        assertEquals(0.5, again.voice.tts.volume);
    }

    @Test
    void validateClampsRecordingLength() {
        ClientConfig c = ClientConfig.defaults();
        c.voice.stt.maxRecordSec = 9999;
        c.validate();
        assertEquals(120, c.voice.stt.maxRecordSec);
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
    void validateClampsTtsValues() {
        ClientConfig c = ClientConfig.defaults();
        c.voice.tts.volume = 5;
        c.voice.tts.maxChars = 9999;
        c.voice.tts.local.speed = 9;
        c.voice.tts.provider = "bogus";
        c.voice.stt.provider = "bogus";
        c.validate();
        assertEquals(1.0, c.voice.tts.volume);
        assertEquals(500, c.voice.tts.maxChars);
        assertEquals(2.0, c.voice.tts.local.speed);
        assertEquals("local", c.voice.tts.provider);
        assertEquals("local", c.voice.stt.provider);

        c.voice.tts.cloud.get("openai").speed = 9;
        c.voice.tts.cloud.get("elevenlabs").speed = 9;
        c.voice.tts.cloud.get("yandex").speed = 9;
        c.validate();
        assertEquals(2.0, c.voice.tts.cloud.get("openai").speed);
        assertEquals(1.2, c.voice.tts.cloud.get("elevenlabs").speed);
        assertEquals(3.0, c.voice.tts.cloud.get("yandex").speed);
    }

    @Test
    void aJsonWithoutVoiceNumbersGetsTheDocumentedDefaults() throws IOException {
        Path file = dir.resolve("friendai-client.json");
        Files.writeString(file, """
            {"version":3,"voice":{"stt":{"provider":"local","local":{"model":"base"}},
             "tts":{"provider":"local","local":{}}}}""");
        ConfigStore<ClientConfig> store = new ConfigStore<>(
                file, ClientConfig.class, ClientConfig::defaults, ClientConfig.CURRENT_VERSION, ClientConfig::migrate);

        ClientConfig c = store.load();
        c.validate();
        assertEquals(30, c.voice.stt.maxRecordSec);
        assertEquals(1.0, c.voice.tts.volume);
        assertEquals(300, c.voice.tts.maxChars);
    }

    @Test
    void zeroRecordLengthAndMaxCharsFallBackToTheDocumentedDefaults() {
        ClientConfig c = ClientConfig.defaults();
        c.voice.stt.maxRecordSec = 0;
        c.voice.tts.maxChars = 0;
        c.validate();
        assertEquals(30, c.voice.stt.maxRecordSec);
        assertEquals(300, c.voice.tts.maxChars);
    }

    @Test
    void zeroVolumeIsAMuteThatSurvivesSaveAndReload() {
        ConfigStore<ClientConfig> store = new ConfigStore<>(
                dir.resolve("friendai-client.json"),
                ClientConfig.class,
                ClientConfig::defaults,
                ClientConfig.CURRENT_VERSION,
                ClientConfig::migrate);
        ClientConfig c = store.load();
        c.voice.tts.volume = 0.0;
        c.validate();
        assertEquals(0.0, c.voice.tts.volume);

        store.save(c);
        ClientConfig again = store.load();
        again.validate();
        assertEquals(0.0, again.voice.tts.volume);
    }

    @Test
    void aZeroSpeedFromGsonMeansNormalSpeed() {
        ClientConfig c = ClientConfig.defaults();
        c.validate();
        c.voice.tts.local.speed = 0;
        c.voice.tts.cloud.get("openai").speed = 0;
        c.validate();
        assertEquals(1.0, c.voice.tts.local.speed);
        assertEquals(1.0, c.voice.tts.cloud.get("openai").speed);
    }

    @Test
    void seedsEachCloudProviderSeparately() {
        ClientConfig c = ClientConfig.defaults();
        c.validate();
        c.voice.tts.cloud.get("openai").apiKey = "sk-openai";
        assertEquals("env:YANDEX_API_KEY", c.voice.tts.cloud.get("yandex").apiKey);
        assertEquals("alena", c.voice.tts.cloud.get("yandex").voice);
        assertEquals("env:GROQ_API_KEY", c.voice.stt.cloud.get("groq").apiKey);
        assertEquals("sk-openai", c.voice.tts.cloud.get("openai").apiKey);
        assertEquals("env:OPENAI_API_KEY", c.voice.stt.cloud.get("openai").apiKey);
    }

    @Test
    void unknownLocalVoiceBecomesTheLanguageDefault() {
        ClientConfig c = ClientConfig.defaults();
        c.voice.tts.local.voice = "not-a-voice";
        c.validate();
        assertNull(c.voice.tts.local.voice);
    }

    @Test
    void knownLocalVoiceIsKept() {
        ClientConfig c = ClientConfig.defaults();
        c.voice.tts.local.voice = "en_GB-alba-medium";
        c.validate();
        assertEquals("en_GB-alba-medium", c.voice.tts.local.voice);
    }

    @Test
    void spatialDefaultsToOnWhenTheFieldIsAbsent() throws IOException {
        Path file = dir.resolve("friendai-client.json");
        Files.writeString(file, """
            {"version":3,"voice":{"stt":{"provider":"local"},"tts":{"provider":"local"}}}""");
        ConfigStore<ClientConfig> store = new ConfigStore<>(
                file, ClientConfig.class, ClientConfig::defaults, ClientConfig.CURRENT_VERSION, ClientConfig::migrate);
        assertTrue(store.load().voice.tts.spatial);
    }
}
