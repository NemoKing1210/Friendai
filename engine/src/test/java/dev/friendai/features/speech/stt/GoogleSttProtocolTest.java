package dev.friendai.features.speech.stt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.friendai.features.speech.PreparedRequest;
import dev.friendai.features.speech.ResolvedStt;
import dev.friendai.foundation.text.UserFacingException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class GoogleSttProtocolTest {
    @Test
    void sendsLinear16WithTheKeyInAHeader() {
        ResolvedStt settings =
                new ResolvedStt("google", "google_stt", "http://localhost:8080", "secret", null, "de-DE", null);
        byte[] pcm = {1, 0, 2, 0};
        PreparedRequest r = new GoogleSttProtocol().prepare(settings, new byte[] {9}, pcm);

        assertEquals("/v1/speech:recognize", r.uri().getPath());
        assertEquals("secret", r.headers().get("X-Goog-Api-Key"));
        assertFalse(r.uri().toString().contains("secret"));
        JsonObject body = JsonParser.parseString(new String(r.body(), StandardCharsets.UTF_8))
                .getAsJsonObject();
        assertEquals("LINEAR16", body.getAsJsonObject("config").get("encoding").getAsString());
        assertEquals("de-DE", body.getAsJsonObject("config").get("languageCode").getAsString());
        assertEquals(
                16000, body.getAsJsonObject("config").get("sampleRateHertz").getAsInt());
        assertEquals(
                Base64.getEncoder().encodeToString(pcm),
                body.getAsJsonObject("audio").get("content").getAsString());
    }

    @Test
    void readsTheFirstTranscriptAndRejectsAnEmptyAnswer() {
        JsonObject response = JsonParser.parseString(
                        "{\"results\":[{\"alternatives\":[{\"transcript\":\" hello \"}]}]}")
                .getAsJsonObject();
        assertEquals("hello", new GoogleSttProtocol().parse(response));

        assertThrows(
                UserFacingException.class,
                () -> new GoogleSttProtocol()
                        .parse(JsonParser.parseString("{\"results\":[]}").getAsJsonObject()));
    }
}
