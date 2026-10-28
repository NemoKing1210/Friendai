package dev.friendai.infrastructure.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class VoiceDraftTest {
    @Test
    void switchingProviderKeepsEveryProvidersValues() {
        ClientConfig c = ClientConfig.defaults();
        c.validate();
        c.voice.stt.provider = "openai";
        c.voice.stt.cloud.get("openai").apiKey = "sk-openai";
        c.voice.stt.cloud.get("yandex").apiKey = "ya-yandex";

        VoiceDraft draft = VoiceDraft.from(c, "yandex", "local");
        draft.applyTo(c);

        assertEquals("yandex", c.voice.stt.provider);
        assertEquals("ya-yandex", c.voice.stt.cloud.get("yandex").apiKey);
        assertEquals("sk-openai", c.voice.stt.cloud.get("openai").apiKey);
    }

    @Test
    void appliesEditsOnlyToTheSelectedProvider() {
        ClientConfig c = ClientConfig.defaults();
        c.validate();
        VoiceDraft draft = new VoiceDraft(
                "local",
                VoiceDraft.from(c).stt(),
                false,
                "yandex",
                new VoiceDraft.Cloud("ya", null, "filipp", "f1", null, "ru-RU"),
                true);

        draft.applyTo(c);

        assertEquals("filipp", c.voice.tts.cloud.get("yandex").voice);
        assertEquals("alloy", c.voice.tts.cloud.get("openai").voice);
        assertEquals("ru-RU", c.voice.tts.cloud.get("yandex").language);
        assertEquals("f1", c.voice.tts.cloud.get("yandex").folderId);
    }

    @Test
    void aLocalProviderNeverWritesACloudEntry() {
        ClientConfig c = ClientConfig.defaults();
        c.validate();
        assertFalse(c.voice.stt.cloud.containsKey("local"));

        new VoiceDraft(
                        "local",
                        new VoiceDraft.Cloud("sk", "m", null, "f", "https://x", "ru"),
                        false,
                        "local",
                        new VoiceDraft.Cloud("sk", "m", "v", "f", "https://x", "ru"),
                        false)
                .applyTo(c);

        assertFalse(c.voice.stt.cloud.containsKey("local"));
        assertFalse(c.voice.tts.cloud.containsKey("local"));
    }

    @Test
    void readsEachServiceForItsOwnProvider() {
        ClientConfig c = ClientConfig.defaults();
        c.validate();
        c.voice.stt.provider = "groq";
        c.voice.stt.cloud.get("groq").model = "whisper-large-v3-turbo";
        c.voice.tts.provider = "yandex";
        c.voice.tts.cloud.get("yandex").voice = "ermil";
        c.voice.tts.cloud.get("yandex").baseUrl = "https://tts.example/v1";

        VoiceDraft draft = VoiceDraft.from(c);

        assertEquals("groq", draft.sttProvider());
        assertEquals("whisper-large-v3-turbo", draft.stt().model());
        assertEquals("yandex", draft.ttsProvider());
        assertEquals("ermil", draft.tts().voice());
        assertEquals("https://tts.example/v1", draft.tts().baseUrl());
    }

    @Test
    void blankValuesAreStoredAsNull() {
        ClientConfig c = ClientConfig.defaults();
        c.validate();

        new VoiceDraft(
                        "openai",
                        new VoiceDraft.Cloud("  ", "", " ", "", "", ""),
                        false,
                        "local",
                        new VoiceDraft.Cloud(null, null, null, null, null, null),
                        false)
                .applyTo(c);

        ClientConfig.CloudSettings openai = c.voice.stt.cloud.get("openai");
        assertNull(openai.apiKey);
        assertNull(openai.model);
        assertNull(openai.voice);
        assertNull(openai.folderId);
        assertNull(openai.baseUrl);
        assertNull(openai.language);
    }

    @Test
    void revealFlagsSurviveARebuild() {
        ClientConfig c = ClientConfig.defaults();
        c.validate();
        VoiceDraft draft = VoiceDraft.from(c);

        VoiceDraft sttShown = draft.withSttReveal(true);
        VoiceDraft bothShown = sttShown.withTtsReveal(true);

        assertFalse(draft.sttReveal());
        assertTrue(sttShown.sttReveal());
        assertFalse(sttShown.ttsReveal());
        assertTrue(bothShown.sttReveal());
        assertTrue(bothShown.ttsReveal());
    }

    @Test
    void toStringNeverPrintsAKey() {
        VoiceDraft draft = new VoiceDraft(
                "openai",
                new VoiceDraft.Cloud("sk-openai-secret", "m", null, null, null, null),
                false,
                "elevenlabs",
                new VoiceDraft.Cloud("el-secret", "m", "voice", null, null, null),
                false);

        assertFalse(draft.toString().contains("sk-openai-secret"));
        assertFalse(draft.toString().contains("el-secret"));
        assertTrue(draft.stt().toString().contains("***"));
        assertTrue(draft.tts().toString().contains("***"));
    }
}
