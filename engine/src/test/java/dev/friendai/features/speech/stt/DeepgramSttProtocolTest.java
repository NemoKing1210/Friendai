package dev.friendai.features.speech.stt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonParser;
import dev.friendai.features.speech.PreparedRequest;
import dev.friendai.features.speech.ResolvedStt;
import dev.friendai.foundation.text.UserFacingException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class DeepgramSttProtocolTest {
    @Test
    void sendsWavWithATokenHeaderAndQueryOptions() {
        ResolvedStt settings =
                new ResolvedStt("deepgram", "deepgram_stt", "http://localhost:8080", "secret", "nova-3", "de", null);
        byte[] wav = {1, 2, 3};
        PreparedRequest r = new DeepgramSttProtocol().prepare(settings, wav, new byte[] {9});

        assertEquals("/v1/listen", r.uri().getPath());
        assertEquals("model=nova-3", r.uri().getQuery().split("&")[1]);
        assertEquals("language=de", r.uri().getQuery().split("&")[2]);
        assertEquals("Token secret", r.headers().get("Authorization"));
        assertEquals("audio/wav", r.contentType());
        assertEquals(3, r.body().length);
        assertFalse(r.uri().toString().contains("secret"));
        assertFalse(new String(r.body(), StandardCharsets.UTF_8).contains("secret"));
    }

    @Test
    void readsTheFirstTranscriptAndRejectsAnEmptyAnswer() {
        String json = "{\"results\":{\"channels\":[{\"alternatives\":[{\"transcript\":\" hi \"}]}]}}";
        assertEquals(
                "hi",
                new DeepgramSttProtocol().parse(JsonParser.parseString(json).getAsJsonObject()));
        String empty = "{\"results\":{\"channels\":[{\"alternatives\":[{\"transcript\":\"\"}]}]}}";
        assertThrows(
                UserFacingException.class,
                () -> new DeepgramSttProtocol()
                        .parse(JsonParser.parseString(empty).getAsJsonObject()));
    }
}
