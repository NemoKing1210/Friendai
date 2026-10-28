package dev.friendai.features.speech;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class VoiceReadinessTest {
    private static ResolvedTts tts(
            String provider, String key, String baseUrl, String model, String voice, double speed, String language) {
        return new ResolvedTts(
                provider,
                provider.equals("local") ? "local" : provider + "_tts",
                baseUrl,
                key,
                model,
                voice,
                language,
                null,
                speed);
    }

    private static ResolvedStt stt(
            String provider, String key, String baseUrl, String model, String language, String folderId) {
        return new ResolvedStt(
                provider,
                provider.equals("local") ? "local" : provider + "_stt",
                baseUrl,
                key,
                model,
                language,
                folderId);
    }

    @Test
    void keylessIsAllowedOnlyWithACustomBaseUrl() {
        ResolvedTts presetDefault = tts("openai", "", "https://api.openai.com/v1", "gpt-4o-mini-tts", "alloy", 1.0, "");
        assertEquals(
                "friendai.voice.tts.error.no_key",
                VoiceReadiness.tts(presetDefault, true).key());

        ResolvedTts localServer = tts("openai", "", "http://localhost:8080/v1", "m", "alloy", 1.0, "");
        assertNull(VoiceReadiness.tts(localServer, true));

        ResolvedStt yandex = stt("yandex", "", "https://stt.api.cloud.yandex.net", null, "ru-RU", null);
        assertEquals(
                "friendai.voice.error.no_key", VoiceReadiness.stt(yandex, true).key());

        ResolvedStt localSttServer = stt("openai", "", "http://localhost:8080/v1", "whisper", "", null);
        assertNull(VoiceReadiness.stt(localSttServer, true));
    }

    @Test
    void yandexNeedsAFolderId() {
        ResolvedStt yandex = stt("yandex", "key", "https://stt.api.cloud.yandex.net", null, "ru-RU", null);
        assertEquals(
                "friendai.voice.error.no_folder",
                VoiceReadiness.stt(yandex, true).key());

        ResolvedStt withFolder = stt("yandex", "key", "https://stt.api.cloud.yandex.net", null, "ru-RU", "f1");
        assertNull(VoiceReadiness.stt(withFolder, true));
    }

    @Test
    void yandexSynthesisNeedsAFolderIdToo() {
        ResolvedTts yandex = tts("yandex", "key", "https://tts.api.cloud.yandex.net", null, "alena", 1.0, "ru-RU");
        assertEquals(
                "friendai.voice.error.no_folder",
                VoiceReadiness.tts(yandex, true).key());

        ResolvedTts withFolder = new ResolvedTts(
                "yandex", "yandex_tts", "https://tts.api.cloud.yandex.net", "key", null, "alena", "ru-RU", "f1", 1.0);
        assertNull(VoiceReadiness.tts(withFolder, true));
    }

    @Test
    void localNeedsItsModelAndVoice() {
        assertEquals(
                "friendai.voice.error.no_model",
                VoiceReadiness.stt(stt("local", "", null, "base", "", null), false)
                        .key());
        assertEquals(
                "friendai.voice.tts.error.no_voice",
                VoiceReadiness.tts(tts("local", "", null, null, "ru_RU-irina-medium", 1.0, ""), false)
                        .key());
        assertNull(VoiceReadiness.tts(tts("local", "", null, null, "ru_RU-irina-medium", 1.0, ""), true));
    }

    @Test
    void aMissingVoiceModelOrLanguageIsReported() {
        ResolvedTts eleven = tts("elevenlabs", "key", "https://api.elevenlabs.io", "em", "", 1.0, "");
        assertEquals(
                "friendai.voice.tts.error.no_voice",
                VoiceReadiness.tts(eleven, true).key());

        ResolvedTts noModel = tts("openai", "key", "https://api.openai.com/v1", "", "alloy", 1.0, "");
        assertEquals(
                "friendai.voice.tts.error.failed",
                VoiceReadiness.tts(noModel, true).key());

        ResolvedStt noSttModel = stt("groq", "key", "https://api.groq.com/openai/v1", "", "", null);
        assertEquals(
                "friendai.voice.error.failed",
                VoiceReadiness.stt(noSttModel, true).key());

        ResolvedTts yandex = tts("yandex", "key", "https://tts.api.cloud.yandex.net", null, "alena", 1.0, "");
        assertEquals(
                "friendai.voice.error.unsupported_language",
                VoiceReadiness.tts(yandex, true).key());
    }
}
