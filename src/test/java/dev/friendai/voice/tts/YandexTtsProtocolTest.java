package dev.friendai.voice.tts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.text.UserFacingException;
import dev.friendai.voice.PreparedRequest;
import dev.friendai.voice.ResolvedTts;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class YandexTtsProtocolTest {
    @Test
    void yandexPostsFormWithSpeed() {
        PreparedRequest r = new YandexTtsProtocol().prepare(tts("ya", "alena", 1.2, "ru-RU", "folder"), "Hi there");

        assertEquals("/speech/v1/tts:synthesize", r.uri().getPath());
        assertEquals("Api-Key ya", r.headers().get("Authorization"));
        assertEquals("application/x-www-form-urlencoded", r.contentType());
        String body = new String(r.body(), StandardCharsets.UTF_8);
        assertTrue(body.contains("text=Hi+there"));
        assertTrue(body.contains("voice=alena"));
        assertTrue(body.contains("speed=1.2"));
        assertTrue(body.contains("folderId=folder"));
        assertTrue(body.contains("lang=ru-RU"));
        assertTrue(body.contains("format=lpcm"));
    }

    @Test
    void theResolvedKeyNeverAppearsInTheRequest() {
        PreparedRequest r =
                new YandexTtsProtocol().prepare(tts("ya-secret-123", "alena", 1.0, "ru-RU", "folder"), "Hi");

        assertFalse(r.uri().toString().contains("ya-secret-123"));
        assertFalse(new String(r.body(), StandardCharsets.UTF_8).contains("ya-secret-123"));
        assertFalse(r.toString().contains("ya-secret-123"));
    }

    @Test
    void aMissingLanguageIsReported() {
        UserFacingException e = assertThrows(
                UserFacingException.class,
                () -> new YandexTtsProtocol().prepare(tts("ya", "alena", 1.0, "", "folder"), "Hi"));
        assertEquals(
                "friendai.voice.error.unsupported_language", e.userMessage().key());
    }

    @Test
    void rawPcmIsReturnedAt16kHz() {
        var audio = new YandexTtsProtocol().parse(new byte[] {1, 0, 2, 0}, tts("ya", "alena", 1.0, "ru-RU", null));
        assertEquals(16000, audio.sampleRateHz());
        assertEquals(4, audio.data().length);
    }

    @Test
    void anEmptyBodyIsReported() {
        UserFacingException e = assertThrows(
                UserFacingException.class,
                () -> new YandexTtsProtocol().parse(new byte[0], tts("ya", "alena", 1.0, "ru-RU", null)));
        assertEquals("friendai.voice.tts.error.failed", e.userMessage().key());
    }

    private static ResolvedTts tts(String key, String voice, double speed, String language, String folderId) {
        return new ResolvedTts(
                "yandex",
                "yandex_tts",
                "https://tts.api.cloud.yandex.net",
                key,
                null,
                voice,
                language,
                folderId,
                speed);
    }
}
