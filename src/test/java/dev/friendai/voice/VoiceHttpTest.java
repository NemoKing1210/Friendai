package dev.friendai.voice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonObject;
import dev.friendai.testutil.MockHttpServer;
import dev.friendai.text.UserFacingException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class VoiceHttpTest {
    private MockHttpServer server;
    private VoiceHttp client;

    @BeforeEach
    void setUp() {
        server = new MockHttpServer();
        client = new VoiceHttp(HttpClient.newHttpClient(), Duration.ofSeconds(5));
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void postsHeadersAndParsesJson() throws Exception {
        server.enqueue(200, "{\"text\":\"hello\"}");
        PreparedRequest request = new PreparedRequest(
                server.baseUri().resolve("/x"),
                Map.of("Authorization", "Bearer sk-secret"),
                "{}".getBytes(),
                "application/json");

        JsonObject out = client.postJson(request).get();

        assertEquals("hello", out.get("text").getAsString());
        assertEquals("Bearer sk-secret", server.requests().get(0).header("authorization"));
        assertEquals("application/json", server.requests().get(0).header("content-type"));
    }

    @Test
    void mapsStatusesAndDoesNotRetry() {
        server.enqueue(401, "{}");
        CompletionException e = assertThrows(
                CompletionException.class, () -> client.postBytes(request()).join());

        assertInstanceOf(UserFacingException.class, e.getCause());
        assertEquals(
                "friendai.voice.error.auth",
                ((UserFacingException) e.getCause()).userMessage().key());
        assertEquals(1, server.requests().size());
    }

    @Test
    void mapsQuotaAndServerErrors() {
        server.enqueue(429, "{}");
        CompletionException quota = assertThrows(
                CompletionException.class, () -> client.postBytes(request()).join());
        assertEquals(
                "friendai.voice.error.quota",
                ((UserFacingException) quota.getCause()).userMessage().key());

        server.enqueue(500, "{}");
        CompletionException failed = assertThrows(
                CompletionException.class, () -> client.postBytes(request()).join());
        assertEquals(
                "friendai.voice.error.failed",
                ((UserFacingException) failed.getCause()).userMessage().key());
        assertEquals(2, server.requests().size());
    }

    @Test
    void rejectsOversizedResponses() {
        server.enqueue(200, "x".repeat(10 * 1024 * 1024 + 1));

        CompletionException e = assertThrows(
                CompletionException.class, () -> client.postBytes(request()).join());

        assertInstanceOf(UserFacingException.class, e.getCause());
        assertEquals(
                "friendai.voice.error.failed",
                ((UserFacingException) e.getCause()).userMessage().key());
    }

    @Test
    void noKeyInErrorMessage() {
        PreparedRequest request = new PreparedRequest(
                URI.create("http://localhost/x"),
                Map.of("Authorization", "Bearer sk-secret"),
                new byte[0],
                "application/json");
        assertFalse(request.toString().contains("sk-secret"));

        server.enqueue(500, "the service echoes sk-secret back");
        CompletionException e = assertThrows(
                CompletionException.class,
                () -> client.postBytes(this.request()).join());
        assertFalse(
                e.getCause().getMessage().contains("sk-secret"), e.getCause().getMessage());
    }

    @Test
    void aNonJsonSuccessBodyIsAnError() {
        server.enqueue(200, "<html>not json</html>");

        CompletionException e = assertThrows(
                CompletionException.class, () -> client.postJson(request()).join());

        assertEquals(
                "friendai.voice.error.failed",
                ((UserFacingException) e.getCause()).userMessage().key());
    }

    private PreparedRequest request() {
        return new PreparedRequest(
                server.baseUri().resolve("/x"),
                Map.of("Authorization", "Bearer sk-secret"),
                "{}".getBytes(),
                "application/json");
    }
}
