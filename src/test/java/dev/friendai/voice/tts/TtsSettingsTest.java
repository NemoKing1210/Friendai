package dev.friendai.voice.tts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.config.ClientConfig;
import dev.friendai.voice.VoiceConfig;
import org.junit.jupiter.api.Test;

class TtsSettingsTest {
    private static TtsSettings settings(ClientConfig.TtsSection cfg, String language) {
        return TtsSettings.from(VoiceConfig.resolveTts(cfg, language, name -> ""), cfg.volume);
    }

    @Test
    void localSettingsUseTheLanguageDefaultVoice() {
        ClientConfig.TtsSection cfg = ClientConfig.defaults().voice.tts;
        TtsSettings ru = settings(cfg, "ru_ru");
        assertTrue(ru.isLocal());
        assertEquals("local", ru.protocol());
        assertEquals("ru_RU-irina-medium", ru.voice());
        assertEquals(1.0, ru.speed());
        assertEquals(1.0, ru.volume());
        assertNull(ru.readinessError());

        TtsSettings en = settings(cfg, "en_us");
        assertEquals("en_US-lessac-medium", en.voice());
    }

    @Test
    void emptyKeyIsAllowedOnlyWithACustomBaseUrl() {
        ClientConfig c = ClientConfig.defaults();
        c.validate();
        c.voice.tts.provider = "openai";
        assertNotNull(settings(c.voice.tts, "en_us").readinessError());
        c.voice.tts.cloud.get("openai").baseUrl = "http://127.0.0.1:8880/v1";
        assertNull(settings(c.voice.tts, "en_us").readinessError());
    }

    @Test
    void elevenLabsNeedsAKeyEvenWithTheDefaultBase() {
        ClientConfig c = ClientConfig.defaults();
        c.validate();
        c.voice.tts.provider = "elevenlabs";
        assertEquals("friendai.voice.tts.error.no_key", settings(c.voice.tts, "en_us").readinessError().key());
    }

    @Test
    void keyIsResolvedButNeverPrinted() {
        ClientConfig c = ClientConfig.defaults();
        c.validate();
        c.voice.tts.provider = "elevenlabs";
        c.voice.tts.cloud.get("elevenlabs").apiKey = "env:ELEVEN_KEY";
        c.voice.tts.cloud.get("elevenlabs").voice = "voice-id";
        TtsSettings s = TtsSettings.from(
                VoiceConfig.resolveTts(c.voice.tts, "en_us", name -> "secret-value"), c.voice.tts.volume);
        assertEquals("secret-value", s.apiKey());
        assertFalse(s.toString().contains("secret-value"));
    }

    @Test
    void providerKeysAreIsolated() {
        ClientConfig c = ClientConfig.defaults();
        c.validate();
        c.voice.tts.cloud.get("openai").apiKey = "env:OPENAI_KEY";
        c.voice.tts.cloud.get("elevenlabs").apiKey = "env:ELEVEN_KEY";
        c.voice.tts.cloud.get("elevenlabs").voice = "voice-id";
        c.voice.tts.provider = "elevenlabs";
        TtsSettings s = TtsSettings.from(
                VoiceConfig.resolveTts(c.voice.tts, "en_us",
                        name -> name.equals("OPENAI_KEY") ? "openai-secret" : "eleven-secret"),
                c.voice.tts.volume);
        assertEquals("eleven-secret", s.apiKey());
    }

    @Test
    void unknownProviderFallsBackToLocal() {
        ClientConfig c = ClientConfig.defaults();
        c.voice.tts.provider = "bogus";
        TtsSettings s = settings(c.voice.tts, "en_us");
        assertTrue(s.isLocal());
        assertEquals("en_US-lessac-medium", s.voice());
    }

    @Test
    void theEngineCacheKeyChangesWhenTheKeyChanges() {
        ClientConfig c = ClientConfig.defaults();
        c.validate();
        ClientConfig.TtsSection cfg = c.voice.tts;
        cfg.provider = "openai";
        cfg.cloud.get("openai").voice = "alloy";
        cfg.cloud.get("openai").apiKey = "first-key";
        TtsSettings first = settings(cfg, "en_us");
        cfg.cloud.get("openai").apiKey = "second-key";
        TtsSettings second = settings(cfg, "en_us");

        assertNotEquals(first.cacheKey(), second.cacheKey());
        assertFalse(first.cacheKey().contains("first-key"));
        assertFalse(second.cacheKey().contains("second-key"));
    }

    @Test
    void equalSettingsShareOneEngineCacheKey() {
        ClientConfig c = ClientConfig.defaults();
        c.validate();
        ClientConfig.TtsSection cfg = c.voice.tts;
        cfg.provider = "elevenlabs";
        cfg.cloud.get("elevenlabs").apiKey = "key";
        cfg.cloud.get("elevenlabs").voice = "voice-id";

        assertEquals(settings(cfg, "en_us").cacheKey(), settings(cfg, "en_us").cacheKey());
    }

    @Test
    void elevenLabsNeedsAConfiguredVoice() {
        ClientConfig c = ClientConfig.defaults();
        c.validate();
        ClientConfig.TtsSection cfg = c.voice.tts;
        cfg.provider = "elevenlabs";
        cfg.cloud.get("elevenlabs").apiKey = "key";
        TtsSettings s = settings(cfg, "en_us");
        assertNull(s.voice());
        assertEquals("friendai.voice.tts.error.no_voice", s.readinessError().key());
    }

    @Test
    void openAiConfiguresSpeedAndVoiceIndependently() {
        ClientConfig c = ClientConfig.defaults();
        c.validate();
        ClientConfig.TtsSection cfg = c.voice.tts;
        cfg.provider = "openai";
        cfg.cloud.get("openai").apiKey = "key";
        cfg.cloud.get("openai").speed = 1.5;
        cfg.cloud.get("openai").voice = "nova";
        cfg.volume = 0.4;
        TtsSettings s = settings(cfg, "en_us");
        assertTrue(s.isOpenAi());
        assertEquals(1.5, s.speed());
        assertEquals("nova", s.voice());
        assertEquals(0.4, s.volume());
    }
}
