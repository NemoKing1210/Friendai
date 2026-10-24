package dev.friendai.voice.tts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class TtsPresetsTest {
    @Test
    void providersAreCompleteAndUnique() {
        List<TtsProvider> all = TtsPresets.all();
        assertEquals(2, all.size());
        assertEquals(Set.of("openai", "elevenlabs"),
                all.stream().map(TtsProvider::id).collect(Collectors.toSet()));
        for (TtsProvider provider : all) {
            assertTrue(provider.defaultBaseUrl().startsWith("https://"), provider.id());
            assertFalse(provider.defaultModel().isBlank(), provider.id());
            assertFalse(provider.defaultVoice().isBlank(), provider.id());
            assertFalse(provider.docsUrl().isBlank(), provider.id());
            assertTrue(provider.paid(), provider.id());
            assertTrue(TtsPresets.byId(provider.id()).isPresent(), provider.id());
        }
        assertTrue(TtsPresets.byId("openai").orElseThrow().editableBaseUrl());
        assertFalse(TtsPresets.byId("elevenlabs").orElseThrow().editableBaseUrl());
        assertTrue(TtsPresets.byId("missing").isEmpty());
    }

    @Test
    void everyRequestKeepsTheKeyInItsHeaderOnly() {
        String key = "secret-key-123";
        for (TtsProvider provider : TtsPresets.all()) {
            TtsProtocol protocol = provider.id().equals("openai")
                    ? new OpenAiSpeechProtocol()
                    : new ElevenLabsSpeechProtocol();
            TtsHttpRequest req = protocol.build(new TtsRequest(
                    provider.defaultBaseUrl(), key, provider.defaultModel(), provider.defaultVoice(), "Hi", 1.0));
            assertFalse(req.uri().toString().contains(key), provider.id());
            assertFalse(new String(req.body(), StandardCharsets.UTF_8).contains(key), provider.id());
            assertFalse(req.toString().contains(key), provider.id());
            assertTrue(req.headers().values().stream().anyMatch(value -> value.contains(key)), provider.id());
        }
    }
}
