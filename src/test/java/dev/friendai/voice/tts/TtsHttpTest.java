package dev.friendai.voice.tts;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.friendai.testutil.MockHttpServer;
import dev.friendai.text.UserFacingException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TtsHttpTest {
    private MockHttpServer server;
    private TtsHttp http;

    @BeforeEach
    void setUp() {
        server = new MockHttpServer();
        http = new TtsHttp(HttpClient.newHttpClient());
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void mapsStatusesToOneUserMessageAndDoesNotRetry() {
        server.enqueue(401, "{}".getBytes(StandardCharsets.UTF_8));
        CompletionException e = assertThrows(CompletionException.class, () -> http.send(protocol(), request()).join());
        assertEquals("friendai.voice.tts.error.auth", ((UserFacingException) e.getCause()).userMessage().key());
        assertEquals(1, server.requests().size());
    }

    @Test
    void mapsQuotaAndServerErrors() {
        server.enqueue(429, new byte[0]);
        CompletionException quota = assertThrows(CompletionException.class, () -> http.send(protocol(), request()).join());
        assertEquals("friendai.voice.tts.error.quota", ((UserFacingException) quota.getCause()).userMessage().key());

        server.enqueue(500, new byte[0]);
        CompletionException failed = assertThrows(CompletionException.class, () -> http.send(protocol(), request()).join());
        assertEquals("friendai.voice.tts.error.failed", ((UserFacingException) failed.getCause()).userMessage().key());
    }

    @Test
    void returnsTheDecodedClipAndKeepsBinaryBytes() {
        byte[] pcm = new byte[] {(byte) 0xFF, (byte) 0x80, 0x00, 0x7F};
        server.enqueue(200, WavCodec.encode(new TtsClip(pcm, 24000)));
        TtsClip clip = http.send(protocol(), request()).join();
        assertEquals(24000, clip.sampleRate());
        assertArrayEquals(pcm, clip.pcm16le());
    }

    private static TtsProtocol protocol() {
        return new OpenAiSpeechProtocol();
    }

    private TtsRequest request() {
        return new TtsRequest(server.baseUrl() + "/v1", "", "tts-1", "alloy", "Hi", 1.0);
    }
}
