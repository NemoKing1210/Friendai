package dev.friendai.voice.tts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.friendai.text.UserFacingException;
import dev.friendai.voice.PcmAudio;
import dev.friendai.voice.PreparedRequest;
import dev.friendai.voice.ResolvedTts;
import dev.friendai.voice.WavCodec;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class OpenAiTtsProtocolTest {
    @Test
    void openAiAsksForWavAndSpeed() {
        ResolvedTts settings = tts("openai", "sk", "http://localhost:8080/v1", "m", "alloy", 1.5, "");
        PreparedRequest r = new OpenAiTtsProtocol().prepare(settings, "Hi");

        assertEquals("/v1/audio/speech", r.uri().getPath());
        assertEquals("Bearer sk", r.headers().get("Authorization"));
        JsonObject body = JsonParser.parseString(new String(r.body(), StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals("wav", body.get("response_format").getAsString());
        assertEquals(1.5, body.get("speed").getAsDouble());
        assertEquals("Hi", body.get("input").getAsString());
        assertEquals("m", body.get("model").getAsString());
        assertEquals("alloy", body.get("voice").getAsString());

        byte[] wav = WavCodec.encode(new float[] {0f, 0.5f}, 24000);
        assertEquals(24000, new OpenAiTtsProtocol().parse(wav, settings).sampleRateHz());
    }

    @Test
    void openAiRejectsANonAudioBody() {
        ResolvedTts settings = tts("openai", "sk", "http://localhost:8080/v1", "m", "alloy", 1.0, "");

        UserFacingException e = assertThrows(UserFacingException.class,
                () -> new OpenAiTtsProtocol().parse("<html>error</html>".getBytes(StandardCharsets.UTF_8), settings));
        assertEquals("friendai.voice.tts.error.failed", e.userMessage().key());
    }

    @Test
    void keylessCustomEndpointSendsNoAuthorizationHeader() {
        PreparedRequest r = new OpenAiTtsProtocol().prepare(
                tts("openai", "", "http://localhost:8080/v1", "m", "alloy", 1.0, ""), "Hi");
        assertFalse(r.headers().containsKey("Authorization"));
    }

    @Test
    void theResolvedKeyNeverAppearsInTheRequest() {
        ResolvedTts settings = tts("openai", "sk-secret-123", "http://localhost:8080/v1", "m", "alloy", 1.0, "");
        PreparedRequest r = new OpenAiTtsProtocol().prepare(settings, "Hi");

        assertFalse(r.uri().toString().contains("sk-secret-123"));
        assertFalse(new String(r.body(), StandardCharsets.UTF_8).contains("sk-secret-123"));
        assertFalse(r.toString().contains("sk-secret-123"));
    }

    @Test
    void parsedAudioCarriesTheSamples() {
        ResolvedTts settings = tts("openai", "sk", "http://localhost:8080/v1", "m", "alloy", 1.0, "");
        byte[] wav = WavCodec.encode(new byte[] {1, 0, 2, 0}, 24000);

        PcmAudio audio = new OpenAiTtsProtocol().parse(wav, settings);

        assertEquals(24000, audio.sampleRateHz());
        assertEquals(4, audio.data().length);
    }

    private static ResolvedTts tts(String provider, String key, String baseUrl, String model,
            String voice, double speed, String language) {
        return new ResolvedTts(provider, provider.equals("local") ? "local" : provider + "_tts",
                baseUrl, key, model, voice, language, null, speed);
    }
}
