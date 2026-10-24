package dev.friendai.voice.tts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class OpenAiSpeechProtocolTest {
    @Test
    void buildsOpenAiRequestAndRedactsTheKey() {
        TtsHttpRequest req = new OpenAiSpeechProtocol()
                .build(new TtsRequest(null, "sk-secret", "gpt-4o-mini-tts", "alloy", "Hi", 1.0));
        assertEquals("POST", req.method());
        assertEquals("https://api.openai.com/v1/audio/speech", req.uri().toString());
        assertEquals("Bearer sk-secret", req.headers().get("Authorization"));
        JsonObject body = JsonParser.parseString(new String(req.body(), StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals("Hi", body.get("input").getAsString());
        assertEquals("wav", body.get("response_format").getAsString());
        assertEquals("gpt-4o-mini-tts", body.get("model").getAsString());
        assertEquals("alloy", body.get("voice").getAsString());
        assertFalse(req.toString().contains("sk-secret"));
    }

    @Test
    void customBaseUrlGetsNoAuthHeaderWhenTheKeyIsEmpty() {
        TtsHttpRequest req = new OpenAiSpeechProtocol()
                .build(new TtsRequest("http://127.0.0.1:8880/v1/", "", "tts-1", "alloy", "Hi", 1.0));
        assertEquals("http://127.0.0.1:8880/v1/audio/speech", req.uri().toString());
        assertFalse(req.headers().containsKey("Authorization"));
    }

    @Test
    void parsesAWholeWavResponse() {
        byte[] wav = WavCodec.encode(new TtsClip(new byte[] {1, 0, 2, 0}, 24000));
        TtsClip clip = new OpenAiSpeechProtocol().parse(wav);
        assertEquals(24000, clip.sampleRate());
        assertEquals(4, clip.pcm16le().length);
    }
}
