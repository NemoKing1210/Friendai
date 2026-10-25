package dev.friendai.voice.stt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.friendai.testutil.MockHttpServer;
import dev.friendai.text.UserFacingException;
import dev.friendai.voice.ResolvedStt;
import dev.friendai.voice.VoiceHttp;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SttClientTest {
    private static final String KEY = "sk-secret-123";

    private MockHttpServer server;
    private SttClient client;

    @BeforeEach
    void setUp() {
        server = new MockHttpServer();
        client = SttClient.withBuiltins(new VoiceHttp(HttpClient.newHttpClient(), Duration.ofSeconds(5)));
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void transcribesThroughMockServer() throws Exception {
        server.enqueue(200, "{\"text\":\"hello\"}");
        String text = client.transcribe(openAi(KEY), new byte[]{1}, new byte[]{2}).get();
        assertEquals("hello", text);
        assertEquals("/v1/audio/transcriptions", server.requests().get(0).path());
        assertEquals("Bearer " + KEY, server.requests().get(0).header("authorization"));
    }

    @Test
    void emptyAnswerIsOnePlayerFacingFailure() {
        server.enqueue(200, "{}");
        CompletionException e = assertThrows(CompletionException.class,
                () -> client.transcribe(openAi(KEY), new byte[0], new byte[0]).join());
        UserFacingException cause = assertInstanceOf(UserFacingException.class, e.getCause());
        assertEquals("friendai.voice.error.failed", cause.userMessage().key());
    }

    @Test
    void nonJsonAnswerIsOnePlayerFacingFailure() {
        server.enqueue(200, "<html>not audio</html>");
        CompletionException e = assertThrows(CompletionException.class,
                () -> client.transcribe(openAi(KEY), new byte[0], new byte[0]).join());
        UserFacingException cause = assertInstanceOf(UserFacingException.class, e.getCause());
        assertEquals("friendai.voice.error.failed", cause.userMessage().key());
    }

    @Test
    void rejectedKeyIsAnAuthFailureWithoutLeakingTheKey() {
        server.enqueue(401, "{\"error\":\"bad key\"}");
        CompletionException e = assertThrows(CompletionException.class,
                () -> client.transcribe(openAi(KEY), new byte[0], new byte[0]).join());
        UserFacingException cause = assertInstanceOf(UserFacingException.class, e.getCause());
        assertEquals("friendai.voice.error.auth", cause.userMessage().key());
        assertFalse(cause.getMessage().contains(KEY));
        assertFalse(client.toString().contains(KEY));
        assertFalse(server.requests().get(0).path().contains(KEY));
        assertFalse(server.requests().get(0).body().contains(KEY));
    }

    @Test
    void unknownProtocolFailsWithoutTouchingTheNetwork() {
        ResolvedStt unknown = new ResolvedStt("openai", "not_a_protocol", server.baseUrl(), "k", "m", "", null);
        CompletionException e = assertThrows(CompletionException.class,
                () -> client.transcribe(unknown, new byte[0], new byte[0]).join());
        UserFacingException cause = assertInstanceOf(UserFacingException.class, e.getCause());
        assertEquals("friendai.voice.error.failed", cause.userMessage().key());
        assertEquals(0, server.requests().size());
    }

    private ResolvedStt openAi(String key) {
        return new ResolvedStt("openai", OpenAiSttProtocol.ID, server.baseUrl() + "/v1",
                key, "gpt-transcribe", "ru", null);
    }
}
