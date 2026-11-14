package dev.friendai.infrastructure.speech;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import dev.friendai.features.speech.PreparedRequest;
import dev.friendai.foundation.text.UserFacingException;
import dev.friendai.testutil.MockHttpServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
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

    @Test
    void readsAChunkedBodyWithoutAContentLength() throws Exception {
        server.enqueueStream(200, List.of("ab", "cd", "ef"));

        byte[] body = client.postBytes(request()).get(2, TimeUnit.SECONDS);

        assertEquals("abcdef", new String(body, StandardCharsets.UTF_8));
    }

    @Test
    void aCancelledRequestDoesNotHoldTheNextOne() throws Exception {
        // This answer is held open after its first chunk, so an exchange that outlived its cancellation would keep
        // the client busy for the whole delay.
        server.enqueueStreamDelayed(200, List.of("first", "second"), 800);
        CompletableFuture<byte[]> held = client.postBytes(request());
        awaitRequestCount(1);

        assertTrue(held.cancel(true), "the caller must be able to abandon the reply at once");
        assertTrue(held.isCancelled());

        server.enqueue(200, "ok");
        long started = System.nanoTime();
        byte[] next = client.postBytes(request()).get(3, TimeUnit.SECONDS);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertArrayEquals("ok".getBytes(StandardCharsets.UTF_8), next);
        assertTrue(elapsedMillis < 1_500, "the cancelled exchange held the client for " + elapsedMillis + " ms");
    }

    private void awaitRequestCount(int expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (server.requests().size() < expected && System.nanoTime() < deadline) {
            try {
                Thread.sleep(5);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private PreparedRequest request() {
        return new PreparedRequest(
                server.baseUri().resolve("/x"),
                Map.of("Authorization", "Bearer sk-secret"),
                "{}".getBytes(),
                "application/json");
    }
}
