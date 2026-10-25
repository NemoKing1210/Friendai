package dev.friendai.voice.tts;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.testutil.MockHttpServer;
import dev.friendai.text.UserFacingException;
import dev.friendai.voice.ResolvedTts;
import dev.friendai.voice.VoiceHttp;
import dev.friendai.voice.WavCodec;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CloudTtsEngineTest {
    private MockHttpServer server;

    @BeforeEach
    void setUp() {
        server = new MockHttpServer();
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private CloudTtsEngine engine(ResolvedTts settings) {
        return new CloudTtsEngine(settings,
                TtsClient.withBuiltins(new VoiceHttp(HttpClient.newHttpClient(), Duration.ofSeconds(5))));
    }

    private ResolvedTts openAi(String baseUrl, String key) {
        return new ResolvedTts("openai", "openai_speech", baseUrl,
                key, "gpt-4o-mini-tts", "alloy", "", null, 1.0);
    }

    @Test
    void synthesizesThroughACustomCompatibleEndpoint() {
        byte[] pcm = {1, 0, 2, 0};
        server.enqueue(200, WavCodec.encode(pcm, 24000));

        TtsClip clip = engine(openAi(server.baseUrl() + "/v1", "")).synthesize("Hello").join();

        assertArrayEquals(pcm, clip.pcm16le());
        assertEquals(24000, clip.sampleRate());
        assertFalse(server.requests().get(0).headers().containsKey("authorization"));
    }

    @Test
    void keyTravelsInAHeader() {
        server.enqueue(200, WavCodec.encode(new byte[] {1, 0}, 24000));
        try (CloudTtsEngine engine = engine(openAi(server.baseUrl() + "/v1", "sk-secret"))) {
            engine.synthesize("Hi").join();
        }
        assertEquals("Bearer sk-secret", server.requests().get(0).header("authorization"));
        assertFalse(server.requests().get(0).path().contains("sk-secret"));
    }

    @Test
    void isReadyUntilClosed() {
        CloudTtsEngine engine = engine(openAi(server.baseUrl() + "/v1", "key"));
        assertTrue(engine.isReady());
        engine.close();
        assertFalse(engine.isReady());
    }

    @Test
    void afterCloseItRefuses() {
        CloudTtsEngine engine = engine(openAi(server.baseUrl() + "/v1", ""));
        engine.close();

        ExecutionException error = assertThrows(ExecutionException.class, () -> engine.synthesize("Hi").get());

        assertInstanceOf(UserFacingException.class, error.getCause());
        assertEquals("friendai.voice.tts.error.failed",
                ((UserFacingException) error.getCause()).userMessage().key());
    }

    @Test
    void aRejectedKeyIsReportedWithoutLeakingIt() {
        server.enqueue(401, "the key sk-secret is invalid");

        ExecutionException error = assertThrows(ExecutionException.class,
                () -> engine(openAi(server.baseUrl() + "/v1", "sk-secret")).synthesize("Hi").get());

        assertInstanceOf(UserFacingException.class, error.getCause());
        UserFacingException cause = (UserFacingException) error.getCause();
        assertEquals("friendai.voice.tts.error.auth", cause.userMessage().key());
        assertFalse(cause.getMessage().contains("sk-secret"), cause.getMessage());
    }

    @Test
    void aServerErrorReadsAsASynthesisFailure() {
        server.enqueue(500, "boom");

        ExecutionException error = assertThrows(ExecutionException.class,
                () -> engine(openAi(server.baseUrl() + "/v1", "key")).synthesize("Hi").get());

        assertInstanceOf(UserFacingException.class, error.getCause());
        assertEquals("friendai.voice.tts.error.failed",
                ((UserFacingException) error.getCause()).userMessage().key());
    }
}
