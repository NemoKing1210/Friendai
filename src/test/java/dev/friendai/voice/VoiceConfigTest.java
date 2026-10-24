package dev.friendai.voice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.config.ClientConfig;
import dev.friendai.voice.tts.VoiceModels;
import org.junit.jupiter.api.Test;

class VoiceConfigTest {
    @Test
    void resolvesCloudKeyFromEnvironment() {
        ClientConfig c = ClientConfig.defaults();
        c.voice.stt.provider = "openai";
        ResolvedStt r = VoiceConfig.resolveStt(c.voice.stt, "en_us",
                name -> name.equals("OPENAI_API_KEY") ? "sk-1" : null);
        assertEquals("openai", r.providerId());
        assertEquals("openai_transcriptions", r.protocol());
        assertEquals("sk-1", r.apiKey());
        assertEquals("gpt-transcribe", r.model());
        assertEquals("https://api.openai.com/v1", r.baseUrl());
        assertEquals("", r.language());
        assertTrue(r.hasKey());
    }

    @Test
    void providerKeysAreIsolated() {
        ClientConfig c = ClientConfig.defaults();
        c.validate();
        c.voice.stt.cloud.get("openai").apiKey = "sk-openai";
        c.voice.stt.provider = "yandex";
        ResolvedStt r = VoiceConfig.resolveStt(c.voice.stt, "ru_ru", name -> null);
        assertEquals("", r.apiKey());
        assertEquals("ru-RU", r.language());
    }

    @Test
    void yandexFolderIdAndLanguageComeFromTheEntry() {
        ClientConfig c = ClientConfig.defaults();
        c.validate();
        c.voice.stt.provider = "yandex";
        c.voice.stt.cloud.get("yandex").apiKey = "ya-key";
        c.voice.stt.cloud.get("yandex").folderId = "folder";
        ResolvedStt r = VoiceConfig.resolveStt(c.voice.stt, "ru_ru", name -> null);
        assertEquals("ya-key", r.apiKey());
        assertEquals("folder", r.folderId());
        assertEquals("yandex_stt", r.protocol());
        assertEquals("ru-RU", r.language());
    }

    @Test
    void resolvedSettingsDoNotPrintTheKey() {
        ClientConfig c = ClientConfig.defaults();
        c.validate();
        c.voice.stt.provider = "openai";
        c.voice.stt.cloud.get("openai").apiKey = "sk-secret";
        ResolvedStt stt = VoiceConfig.resolveStt(c.voice.stt, "en_us", name -> null);
        c.voice.tts.provider = "openai";
        c.voice.tts.cloud.get("openai").apiKey = "sk-secret";
        ResolvedTts tts = VoiceConfig.resolveTts(c.voice.tts, "en_us", name -> null);

        assertEquals("openai", stt.providerId());
        assertFalse(stt.toString().contains("sk-secret"), stt.toString());
        assertFalse(tts.toString().contains("sk-secret"), tts.toString());
        assertFalse(tts.toString().contains("sk"), tts.toString());
    }

    @Test
    void localTtsUsesTheLanguageDefaultVoiceAndItsSpeed() {
        ClientConfig c = ClientConfig.defaults();
        c.voice.tts.local.speed = 1.3;
        ResolvedTts ru = VoiceConfig.resolveTts(c.voice.tts, "ru_ru", name -> null);
        assertEquals("local", ru.providerId());
        assertEquals("local", ru.protocol());
        assertEquals("ru_RU-irina-medium", ru.voice());
        assertEquals(1.3, ru.speed());
        assertFalse(ru.hasKey());
        assertEquals("", ru.language());

        assertEquals("en_US-lessac-medium",
                VoiceConfig.resolveTts(c.voice.tts, "en_us", name -> null).voice());
    }

    @Test
    void cloudTtsUsesThePresetDefaultsAndIgnoresLanguage() {
        ClientConfig c = ClientConfig.defaults();
        c.validate();
        c.voice.tts.provider = "elevenlabs";
        ResolvedTts r = VoiceConfig.resolveTts(c.voice.tts, "ru_ru", name -> null);
        assertEquals("elevenlabs_tts", r.protocol());
        assertEquals("https://api.elevenlabs.io", r.baseUrl());
        assertEquals("eleven_multilingual_v2", r.model());
        assertNull(r.voice());
        assertEquals("", r.language());
        assertEquals(1.0, r.speed());
    }

    @Test
    void yandexTtsLanguageNeedsAConcreteTag() {
        ClientConfig c = ClientConfig.defaults();
        c.validate();
        c.voice.tts.provider = "yandex";
        c.voice.tts.cloud.get("yandex").apiKey = "ya";
        assertEquals("ru-RU", VoiceConfig.resolveTts(c.voice.tts, "ru_ru", name -> null).language());
        assertEquals("en-US", VoiceConfig.resolveTts(c.voice.tts, "de_de", name -> null).language());

        c.voice.tts.cloud.get("yandex").language = "kk-KK";
        assertEquals("kk-KK", VoiceConfig.resolveTts(c.voice.tts, "ru_ru", name -> null).language());
    }

    @Test
    void ttsCacheKeyNeverContainsTheKeyButChangesWithIt() {
        ClientConfig c = ClientConfig.defaults();
        c.validate();
        c.voice.tts.provider = "openai";
        c.voice.tts.cloud.get("openai").apiKey = "first-key";
        String first = VoiceConfig.ttsCacheKey(VoiceConfig.resolveTts(c.voice.tts, "en_us", name -> null));
        c.voice.tts.cloud.get("openai").apiKey = "second-key";
        String second = VoiceConfig.ttsCacheKey(VoiceConfig.resolveTts(c.voice.tts, "en_us", name -> null));

        assertNotEquals(first, second);
        assertFalse(first.contains("first-key"));
        assertFalse(second.contains("second-key"));
        assertTrue(first.contains("openai"));
        assertTrue(first.contains("alloy"));
    }

    @Test
    void unknownProvidersFallBackToLocal() {
        ClientConfig c = ClientConfig.defaults();
        c.voice.stt.provider = "nope";
        c.voice.tts.provider = "nope";
        assertEquals("local", VoiceConfig.resolveStt(c.voice.stt, "en_us", name -> null).providerId());
        assertEquals("local", VoiceConfig.resolveTts(c.voice.tts, "en_us", name -> null).providerId());
    }

    @Test
    void localVoiceCatalogDefaultsFollowLanguage() {
        assertEquals("ru_RU-irina-medium", VoiceModels.defaultFor("ru_ru").id());
        assertEquals("en_US-lessac-medium", VoiceModels.defaultFor("en_us").id());
        assertTrue(VoiceModels.byId("missing").isEmpty());
    }
}
