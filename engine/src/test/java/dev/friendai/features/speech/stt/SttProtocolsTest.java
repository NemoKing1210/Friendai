package dev.friendai.features.speech.stt;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.friendai.features.speech.VoicePreset;
import org.junit.jupiter.api.Test;

/**
 * The protocol ids registered in {@link SttClient} must be exactly the ids carried by
 * {@link SttPresets}; otherwise every cloud call silently fails on an unknown protocol.
 */
class SttProtocolsTest {
    @Test
    void everyPresetUsesARegisteredProtocol() {
        assertEquals(OpenAiSttProtocol.ID, protocolOf("openai"));
        assertEquals(OpenAiSttProtocol.ID, protocolOf("groq"));
        assertEquals(YandexSttProtocol.ID, protocolOf("yandex"));
    }

    private static String protocolOf(String presetId) {
        VoicePreset preset = SttPresets.require(presetId);
        return preset.protocol();
    }
}
