package dev.friendai.voice.tts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.friendai.foundation.text.UserFacingException;
import dev.friendai.testutil.MockHttpServer;
import dev.friendai.voice.PcmAudio;
import dev.friendai.voice.PreparedRequest;
import dev.friendai.voice.ResolvedTts;
import dev.friendai.voice.VoiceHttp;
import dev.friendai.voice.WavCodec;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TtsClientTest {
    private MockHttpServer server;
    private TtsClient client;

    @BeforeEach
    void setUp() {
        server = new MockHttpServer();
        client = TtsClient.withBuiltins(new VoiceHttp(HttpClient.newHttpClient(), Duration.ofSeconds(5)));
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void synthesizesThroughMockServer() throws Exception {
        byte[] pcm = {(byte) 0xFF, (byte) 0x80, 0x00, 0x7F};
        server.enqueue(200, WavCodec.encode(pcm, 24000));

        PcmAudio audio = client.synthesize(openAi(""), "Hi").get();

        assertEquals(pcm.length, audio.data().length);
        assertEquals(24000, audio.sampleRateHz());
        assertEquals("/v1/audio/speech", server.requests().get(0).path());
    }

    @Test
    void anUnknownProtocolFailsWithoutSending() {
        ResolvedTts settings =
                new ResolvedTts("weird", "weird_tts", server.baseUri() + "/v1", "sk", "m", "alloy", "", null, 1.0);

        CompletionException e = assertThrows(
                CompletionException.class,
                () -> client.synthesize(settings, "Hi").join());

        assertInstanceOf(UserFacingException.class, e.getCause());
        assertEquals(
                "friendai.voice.tts.error.failed",
                ((UserFacingException) e.getCause()).userMessage().key());
        assertEquals(0, server.requests().size());
    }

    @Test
    void theKeyNeverReachesTheUrlBodyOrAMessage() {
        server.enqueue(401, "rejected sk-secret-123");

        CompletionException e = assertThrows(
                CompletionException.class,
                () -> client.synthesize(openAi("sk-secret-123"), "Hi").join());

        assertInstanceOf(UserFacingException.class, e.getCause());
        assertFalse(e.getCause().getMessage().contains("sk-secret-123"));
        var recorded = server.requests().get(0);
        assertFalse(recorded.path().contains("sk-secret-123"));
        assertFalse(recorded.body().contains("sk-secret-123"));
        assertEquals("Bearer sk-secret-123", recorded.header("authorization"));
    }

    @Test
    void aPreparedRequestAlsoHidesTheKey() {
        PreparedRequest request = new OpenAiTtsProtocol().prepare(openAi("sk-secret-123"), "Hi");
        assertFalse(request.uri().toString().contains("sk-secret-123"));
        assertFalse(new String(request.body(), StandardCharsets.UTF_8).contains("sk-secret-123"));
        assertFalse(request.toString().contains("sk-secret-123"));
    }

    @Test
    void anInvalidBaseUrlBecomesTheOnePlayerFacingFailure() {
        ResolvedTts settings =
                new ResolvedTts("elevenlabs", "elevenlabs_tts", "not a uri", "key", "m", "voice", "", null, 1.0);

        CompletionException e = assertThrows(
                CompletionException.class,
                () -> client.synthesize(settings, "Hi").join());

        assertInstanceOf(UserFacingException.class, e.getCause());
        assertEquals(
                "friendai.voice.tts.error.failed",
                ((UserFacingException) e.getCause()).userMessage().key());
        assertEquals(0, server.requests().size());
    }

    private ResolvedTts openAi(String key) {
        return new ResolvedTts(
                "openai", "openai_speech", server.baseUri() + "/v1", key, "gpt-4o-mini-tts", "alloy", "", null, 1.0);
    }
}
