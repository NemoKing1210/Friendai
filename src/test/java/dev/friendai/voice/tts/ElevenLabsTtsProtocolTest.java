package dev.friendai.voice.tts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import dev.friendai.text.UserFacingException;
import dev.friendai.voice.PreparedRequest;
import dev.friendai.voice.ResolvedTts;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ElevenLabsTtsProtocolTest {
    @Test
    void elevenLabsUsesPcmQueryAndCarriesTheKeyInAHeader() {
        PreparedRequest r =
                new ElevenLabsTtsProtocol().prepare(tts("el-secret", "eleven_multilingual_v2", "voice-1", 1.1), "Hi");

        assertEquals("/v1/text-to-speech/voice-1", r.uri().getPath());
        assertTrue(r.uri().getQuery().contains("output_format=pcm_24000"));
        assertEquals("el-secret", r.headers().get("xi-api-key"));
        var body = JsonParser.parseString(new String(r.body(), StandardCharsets.UTF_8))
                .getAsJsonObject();
        assertEquals("eleven_multilingual_v2", body.get("model_id").getAsString());
        assertEquals("Hi", body.get("text").getAsString());
        assertEquals(1.1, body.getAsJsonObject("voice_settings").get("speed").getAsDouble(), 0.0001);
    }

    @Test
    void theResolvedKeyNeverAppearsInTheRequest() {
        PreparedRequest r = new ElevenLabsTtsProtocol()
                .prepare(tts("el-secret-123", "eleven_multilingual_v2", "voice-1", 1.0), "Hi");

        assertFalse(r.uri().toString().contains("el-secret-123"));
        assertFalse(new String(r.body(), StandardCharsets.UTF_8).contains("el-secret-123"));
        assertFalse(r.toString().contains("el-secret-123"));
    }

    @Test
    void blankVoiceIsReported() {
        UserFacingException e = assertThrows(
                UserFacingException.class,
                () -> new ElevenLabsTtsProtocol().prepare(tts("key", "model", "  ", 1.0), "Hi"));
        assertEquals("friendai.voice.tts.error.no_voice", e.userMessage().key());
    }

    @Test
    void theVoiceIdIsUrlEncodedAsOnePathSegment() {
        PreparedRequest r = new ElevenLabsTtsProtocol().prepare(tts("key", "model", "voice/with space?", 1.0), "Hi");

        assertEquals("/v1/text-to-speech/voice%2Fwith%20space%3F", r.uri().getRawPath());
    }

    @Test
    void rawPcmIsReturnedAsAClip() {
        byte[] pcm = {1, 0, 2, 0};

        var audio = new ElevenLabsTtsProtocol().parse(pcm, tts("key", "model", "voice-1", 1.0));

        assertEquals(24000, audio.sampleRateHz());
        org.junit.jupiter.api.Assertions.assertArrayEquals(pcm, audio.data());
    }

    @Test
    void anEmptyBodyIsReported() {
        UserFacingException e = assertThrows(
                UserFacingException.class,
                () -> new ElevenLabsTtsProtocol().parse(new byte[0], tts("key", "model", "voice-1", 1.0)));
        assertEquals("friendai.voice.tts.error.failed", e.userMessage().key());
    }

    private static ResolvedTts tts(String key, String model, String voice, double speed) {
        return new ResolvedTts(
                "elevenlabs", "elevenlabs_tts", "https://api.elevenlabs.io", key, model, voice, "", null, speed);
    }
}
