package dev.friendai.features.speech.tts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.friendai.features.speech.PreparedRequest;
import dev.friendai.features.speech.ResolvedTts;
import dev.friendai.features.speech.WavCodec;
import dev.friendai.foundation.text.UserFacingException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class GoogleTtsProtocolTest {
    @Test
    void asksForLinear16WithTheVoiceAndLanguage() {
        ResolvedTts settings = tts("secret", "http://localhost:8080", "de-DE-Neural2-F", "de-DE", 1.2);
        PreparedRequest r = new GoogleTtsProtocol().prepare(settings, "Hallo");

        assertEquals("/v1/text:synthesize", r.uri().getPath());
        assertEquals("secret", r.headers().get("X-Goog-Api-Key"));
        assertFalse(r.uri().toString().contains("secret"));
        JsonObject body = JsonParser.parseString(new String(r.body(), StandardCharsets.UTF_8))
                .getAsJsonObject();
        assertEquals("Hallo", body.getAsJsonObject("input").get("text").getAsString());
        assertEquals("de-DE", body.getAsJsonObject("voice").get("languageCode").getAsString());
        assertEquals(
                "de-DE-Neural2-F", body.getAsJsonObject("voice").get("name").getAsString());
        assertEquals(
                "LINEAR16",
                body.getAsJsonObject("audioConfig").get("audioEncoding").getAsString());
        assertEquals(
                1.2, body.getAsJsonObject("audioConfig").get("speakingRate").getAsDouble());
    }

    @Test
    void parsesBase64WavAndRawLinear16() {
        ResolvedTts settings = tts("secret", "http://localhost:8080", "en-US-Neural2-F", "en-US", 1.0);
        byte[] wav = WavCodec.encode(new byte[] {1, 0, 2, 0}, 24000);
        JsonObject wrapped = new JsonObject();
        wrapped.addProperty("audioContent", Base64.getEncoder().encodeToString(wav));
        assertEquals(
                24000,
                new GoogleTtsProtocol()
                        .parse(wrapped.toString().getBytes(StandardCharsets.UTF_8), settings)
                        .sampleRateHz());

        JsonObject raw = new JsonObject();
        raw.addProperty("audioContent", Base64.getEncoder().encodeToString(new byte[] {1, 0, 2, 0}));
        assertEquals(
                24000,
                new GoogleTtsProtocol()
                        .parse(raw.toString().getBytes(StandardCharsets.UTF_8), settings)
                        .sampleRateHz());
    }

    @Test
    void anErrorBodyIsAFailure() {
        ResolvedTts settings = tts("secret", "http://localhost:8080", "en-US-Neural2-F", "en-US", 1.0);
        assertThrows(
                UserFacingException.class,
                () -> new GoogleTtsProtocol().parse("<html>error</html>".getBytes(StandardCharsets.UTF_8), settings));
    }

    private static ResolvedTts tts(String key, String baseUrl, String voice, String language, double speed) {
        return new ResolvedTts("google", "google_tts", baseUrl, key, null, voice, language, null, speed);
    }
}
