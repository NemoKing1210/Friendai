package dev.friendai.features.speech;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.features.speech.stt.SttPresets;
import dev.friendai.features.speech.tts.TtsPresets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class VoicePresetsTest {
    @Test
    void presetsCoverEveryAdvertisedProvider() {
        assertEquals(List.of("local", "openai", "google", "deepgram", "groq", "yandex"), SttPresets.ids());
        assertEquals(List.of("local", "openai", "google", "elevenlabs", "yandex"), TtsPresets.ids());
        assertEquals("gpt-transcribe", SttPresets.require("openai").defaultModel());
        assertEquals("whisper-large-v3", SttPresets.require("groq").defaultModel());
        assertTrue(SttPresets.require("yandex").uses(CloudField.FOLDER_ID));
        assertEquals("alena", TtsPresets.require("yandex").defaultVoice());
        assertTrue(TtsPresets.require("yandex").uses(CloudField.LANGUAGE));
        assertTrue(SttPresets.require("openai").uses(CloudField.BASE_URL));
        assertEquals("local", SttPresets.require("nope").id());
    }

    @Test
    void authHeadersNeverUseQueryParameters() {
        assertEquals(Map.of("Authorization", "Bearer sk"), VoiceAuth.BEARER.headers("sk"));
        assertEquals(Map.of("xi-api-key", "el"), VoiceAuth.ELEVENLABS.headers("el"));
        assertEquals(Map.of("Authorization", "Api-Key ya"), VoiceAuth.YANDEX.headers("ya"));
        assertEquals(Map.of(), VoiceAuth.BEARER.headers(null));
    }
}
