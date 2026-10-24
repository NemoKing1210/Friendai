package dev.friendai.voice.tts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.config.ClientConfig;
import org.junit.jupiter.api.Test;

class TtsSettingsTest {
    @Test
    void localSettingsUseTheLanguageDefaultVoice() {
        ClientConfig.TtsSection cfg = ClientConfig.defaults().voice.tts;
        TtsSettings ru = TtsSettings.from(cfg, "ru_ru", name -> "");
        assertTrue(ru.isLocal());
        assertEquals("ru_RU-irina-medium", ru.voice());
        assertEquals(1.0, ru.speed());
        assertEquals(1.0, ru.volume());
        assertNull(ru.readinessError());

        TtsSettings en = TtsSettings.from(cfg, "en_us", name -> "");
        assertEquals("en_US-lessac-medium", en.voice());
    }

    @Test
    void emptyKeyIsAllowedOnlyWithACustomBaseUrl() {
        ClientConfig.TtsSection cfg = ClientConfig.defaults().voice.tts;
        cfg.provider = "openai";
        assertNotNull(TtsSettings.from(cfg, "en_us", name -> "").readinessError());
        cfg.openai.baseUrl = "http://127.0.0.1:8880/v1";
        assertNull(TtsSettings.from(cfg, "en_us", name -> "").readinessError());
    }

    @Test
    void elevenLabsNeedsAKeyEvenWithTheDefaultBase() {
        ClientConfig.TtsSection cfg = ClientConfig.defaults().voice.tts;
        cfg.provider = "elevenlabs";
        assertEquals("friendai.voice.tts.error.no_key",
                TtsSettings.from(cfg, "en_us", name -> "").readinessError().key());
    }

    @Test
    void keyIsResolvedButNeverPrinted() {
        ClientConfig.TtsSection cfg = ClientConfig.defaults().voice.tts;
        cfg.provider = "elevenlabs";
        cfg.elevenlabs.apiKey = "env:ELEVEN_KEY";
        cfg.elevenlabs.voice = "voice-id";
        TtsSettings s = TtsSettings.from(cfg, "en_us", name -> "secret-value");
        assertEquals("secret-value", s.apiKey());
        assertFalse(s.toString().contains("secret-value"));
    }

    @Test
    void providerKeysAreIsolated() {
        ClientConfig.TtsSection cfg = ClientConfig.defaults().voice.tts;
        cfg.openai.apiKey = "env:OPENAI_KEY";
        cfg.elevenlabs.apiKey = "env:ELEVEN_KEY";
        cfg.provider = "elevenlabs";
        cfg.elevenlabs.voice = "voice-id";
        TtsSettings s = TtsSettings.from(cfg, "en_us",
                name -> name.equals("OPENAI_KEY") ? "openai-secret" : "eleven-secret");
        assertEquals("eleven-secret", s.apiKey());
    }

    @Test
    void unknownProviderFallsBackToLocal() {
        ClientConfig.TtsSection cfg = ClientConfig.defaults().voice.tts;
        cfg.provider = "bogus";
        TtsSettings s = TtsSettings.from(cfg, "en_us", name -> "");
        assertTrue(s.isLocal());
        assertEquals("en_US-lessac-medium", s.voice());
    }

    @Test
    void elevenLabsUsesThePresetVoiceWhenNoneIsConfigured() {
        ClientConfig.TtsSection cfg = ClientConfig.defaults().voice.tts;
        cfg.provider = "elevenlabs";
        cfg.elevenlabs.apiKey = "key";
        TtsSettings s = TtsSettings.from(cfg, "en_us", name -> "");
        assertEquals("21m00Tcm4TlvDq8ikWAM", s.voice());
        assertNull(s.readinessError());
    }
}
