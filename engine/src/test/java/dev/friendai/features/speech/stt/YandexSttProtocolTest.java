package dev.friendai.features.speech.stt;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import dev.friendai.foundation.text.UserFacingException;
import dev.friendai.features.speech.PreparedRequest;
import dev.friendai.features.speech.ResolvedStt;
import org.junit.jupiter.api.Test;

class YandexSttProtocolTest {
    @Test
    void sendsRawPcmWithLanguageAndFolder() {
        PreparedRequest r = new YandexSttProtocol()
                .prepare(stt("yandex", "ya", null, "ru-RU", "f1"), new byte[] {1}, new byte[] {2});
        assertTrue(r.uri().getQuery().contains("lang=ru-RU"));
        assertTrue(r.uri().getQuery().contains("folderId=f1"));
        assertEquals("application/octet-stream", r.contentType());
    }

    @Test
    void usesTheRecognitionPathAndRawSamples() {
        PreparedRequest r = new YandexSttProtocol()
                .prepare(stt("yandex", "ya-1", null, "ru-RU", "folder 1"), new byte[] {9}, new byte[] {4, 5});
        assertEquals("/speech/v1/stt:recognize", r.uri().getPath());
        assertTrue(r.uri().getQuery().contains("format=lpcm"));
        assertTrue(r.uri().getQuery().contains("sampleRateHertz=16000"));
        assertTrue(r.uri().getQuery().contains("folderId=folder+1"));
        assertEquals("Api-Key ya-1", r.headers().get("Authorization"));
        assertArrayEquals(new byte[] {4, 5}, r.body());
    }

    @Test
    void omitsAFolderIdThatIsNotConfigured() {
        PreparedRequest r =
                new YandexSttProtocol().prepare(stt("yandex", "ya", null, "en-US", null), new byte[0], new byte[0]);
        assertFalse(r.uri().getQuery().contains("folderId"));
    }

    @Test
    void missingLanguageIsPlayerFacing() {
        UserFacingException e = assertThrows(
                UserFacingException.class,
                () -> new YandexSttProtocol().prepare(stt("yandex", "ya", null, "", null), new byte[0], new byte[0]));
        assertEquals(
                "friendai.voice.error.unsupported_language", e.userMessage().key());
    }

    @Test
    void parsesTheResult() {
        assertEquals(
                "hi",
                new YandexSttProtocol()
                        .parse(JsonParser.parseString("{\"result\":\" hi \"}").getAsJsonObject()));
    }

    @Test
    void emptyResponseIsAPlayerFacingFailure() {
        UserFacingException e = assertThrows(
                UserFacingException.class,
                () -> new YandexSttProtocol().parse(JsonParser.parseString("{}").getAsJsonObject()));
        assertEquals("friendai.voice.error.failed", e.userMessage().key());
    }

    @Test
    void malformedResultIsAPlayerFacingFailureRatherThanAnException() {
        UserFacingException e = assertThrows(
                UserFacingException.class,
                () -> new YandexSttProtocol()
                        .parse(JsonParser.parseString("{\"result\":[]}").getAsJsonObject()));
        assertEquals("friendai.voice.error.failed", e.userMessage().key());
    }

    @Test
    void keyNeverAppearsInTheUrlBodyOrToString() {
        ResolvedStt settings = stt("yandex", "ya-secret-123", null, "ru-RU", "f1");
        PreparedRequest r = new YandexSttProtocol().prepare(settings, new byte[] {1}, new byte[] {2});
        assertFalse(r.uri().toString().contains("ya-secret-123"));
        assertFalse(new String(r.body()).contains("ya-secret-123"));
        assertFalse(r.toString().contains("ya-secret-123"));
        assertFalse(settings.toString().contains("ya-secret-123"));
        assertEquals("Api-Key ya-secret-123", r.headers().get("Authorization"));
    }

    private static ResolvedStt stt(String provider, String key, String model, String language, String folderId) {
        return OpenAiSttProtocolTest.stt(provider, key, model, language, folderId);
    }
}
