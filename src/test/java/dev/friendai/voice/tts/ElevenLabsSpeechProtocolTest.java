package dev.friendai.voice.tts;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.friendai.text.UserFacingException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ElevenLabsSpeechProtocolTest {
    @Test
    void buildsPcmRequestAndRedactsTheKey() {
        TtsHttpRequest req = new ElevenLabsSpeechProtocol()
                .build(new TtsRequest(null, "el-secret", "eleven_multilingual_v2", "21m00Tcm4TlvDq8ikWAM", "Hi", 1.1));
        assertEquals(
                "https://api.elevenlabs.io/v1/text-to-speech/21m00Tcm4TlvDq8ikWAM?output_format=pcm_24000",
                req.uri().toString());
        assertEquals("el-secret", req.headers().get("xi-api-key"));
        JsonObject body = JsonParser.parseString(new String(req.body(), StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals("Hi", body.get("text").getAsString());
        assertEquals("eleven_multilingual_v2", body.get("model_id").getAsString());
        assertEquals(1.1, body.getAsJsonObject("voice_settings").get("speed").getAsDouble(), 0.0001);
        assertFalse(req.toString().contains("el-secret"));
    }

    @Test
    void rawPcmIsReturnedAsAClip() {
        byte[] pcm = new byte[] {1, 0, 2, 0};
        TtsClip clip = new ElevenLabsSpeechProtocol().parse(pcm);
        assertEquals(24000, clip.sampleRate());
        assertArrayEquals(pcm, clip.pcm16le());
    }

    @Test
    void blankVoiceIsReported() {
        UserFacingException e = assertThrows(UserFacingException.class, () -> new ElevenLabsSpeechProtocol()
                .build(new TtsRequest(null, "key", "model", "  ", "Hi", 1.0)));
        assertEquals("friendai.voice.tts.error.no_voice", e.userMessage().key());
    }
}
