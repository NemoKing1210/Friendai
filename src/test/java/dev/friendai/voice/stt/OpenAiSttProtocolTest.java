package dev.friendai.voice.stt;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import dev.friendai.text.UserFacingException;
import dev.friendai.voice.PreparedRequest;
import dev.friendai.voice.ResolvedStt;
import org.junit.jupiter.api.Test;

class OpenAiSttProtocolTest {
    @Test
    void sendsMultipartWav() {
        PreparedRequest r = new OpenAiSttProtocol().prepare(stt("openai", "sk", "m", "", null),
                new byte[]{1}, new byte[]{2});
        assertEquals("/audio/transcriptions", r.uri().getPath());
        assertEquals("Bearer sk", r.headers().get("Authorization"));
        assertTrue(r.contentType().startsWith("multipart/form-data"));
        assertTrue(new String(r.body(), UTF_8).contains("name=\"model\""));
    }

    @Test
    void sendsTheRecordingAsAFileWithTheConfiguredLanguage() {
        PreparedRequest r = new OpenAiSttProtocol().prepare(stt("openai", "sk", "whisper-x", "ru", null),
                new byte[]{7, 8}, new byte[]{9});
        String body = new String(r.body(), UTF_8);
        assertTrue(body.contains("name=\"file\"; filename=\"audio.wav\""));
        assertTrue(body.contains("name=\"language\""));
        assertTrue(body.contains("whisper-x"));
        assertTrue(r.contentType().startsWith("multipart/form-data; boundary="));
    }

    @Test
    void omitsTheLanguageFieldWhenNoLanguageIsSet() {
        PreparedRequest r = new OpenAiSttProtocol().prepare(stt("openai", "sk", "m", "", null),
                new byte[0], new byte[0]);
        assertFalse(new String(r.body(), UTF_8).contains("name=\"language\""));
    }

    @Test
    void joinsTheConfiguredBaseUrlWithoutDoubleSlashes() {
        PreparedRequest r = new OpenAiSttProtocol().prepare(
                new ResolvedStt("groq", OpenAiSttProtocol.ID, "https://api.groq.com/openai/v1/", "k",
                        "whisper-large-v3", "", null),
                new byte[0], new byte[0]);
        assertEquals("https://api.groq.com/openai/v1/audio/transcriptions", r.uri().toString().split("\\?")[0]);
    }

    @Test
    void parsesTheText() {
        assertEquals("hi", new OpenAiSttProtocol()
                .parse(JsonParser.parseString("{\"text\":\" hi \"}").getAsJsonObject()));
    }

    @Test
    void emptyResponseIsAPlayerFacingFailure() {
        UserFacingException e = assertThrows(UserFacingException.class,
                () -> new OpenAiSttProtocol().parse(JsonParser.parseString("{}").getAsJsonObject()));
        assertEquals("friendai.voice.error.failed", e.userMessage().key());
    }

    @Test
    void malformedTextIsAPlayerFacingFailureRatherThanAnException() {
        UserFacingException e = assertThrows(UserFacingException.class,
                () -> new OpenAiSttProtocol().parse(JsonParser.parseString("{\"text\":{}}").getAsJsonObject()));
        assertEquals("friendai.voice.error.failed", e.userMessage().key());
    }

    @Test
    void keyNeverAppearsInTheUrlBodyOrToString() {
        ResolvedStt settings = stt("openai", "sk-secret-123", "m", "ru", null);
        PreparedRequest r = new OpenAiSttProtocol().prepare(settings, new byte[]{1}, new byte[]{2});
        assertFalse(r.uri().toString().contains("sk-secret-123"));
        assertFalse(new String(r.body(), UTF_8).contains("sk-secret-123"));
        assertFalse(r.toString().contains("sk-secret-123"));
        assertFalse(settings.toString().contains("sk-secret-123"));
        assertEquals("Bearer sk-secret-123", r.headers().get("Authorization"));
    }

    static ResolvedStt stt(String provider, String key, String model, String language, String folderId) {
        // An empty base keeps the asserted paths exactly as the protocol appends them.
        return new ResolvedStt(provider, protocol(provider), "", key, model, language, folderId);
    }

    private static String protocol(String provider) {
        return provider.equals("yandex") ? "yandex_stt" : OpenAiSttProtocol.ID;
    }
}
