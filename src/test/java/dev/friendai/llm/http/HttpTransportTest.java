package dev.friendai.llm.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.friendai.agent.llm.model.LlmErrorKind;
import dev.friendai.agent.llm.model.LlmException;
import dev.friendai.testutil.MockHttpServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HttpTransportTest {
    private MockHttpServer server;
    private HttpTransport transport;

    @BeforeEach
    void setUp() {
        server = new MockHttpServer();
        transport = new HttpTransport(HttpClient.newHttpClient(), Duration.ofSeconds(5), 2, Duration.ofMillis(1));
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private URI uri() {
        return server.baseUri().resolve("/x");
    }

    private static LlmException failure(CompletableFuture<?> f) {
        try {
            f.get();
            throw new AssertionError("expected failure");
        } catch (ExecutionException e) {
            return (LlmException) e.getCause();
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonObject body(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    void postsJsonAndParsesResponse() throws Exception {
        server.enqueue(200, "{\"ok\":true}");
        JsonObject result =
                transport.postJson(uri(), Map.of(), body("{\"a\":1}")).get();
        assertTrue(result.get("ok").getAsBoolean());
        MockHttpServer.RecordedRequest r = server.requests().get(0);
        assertEquals("{\"a\":1}", r.body());
        assertEquals("application/json", r.header("Content-Type"));
    }

    @Test
    void maps401ToAuth() {
        server.enqueue(401, "{}");
        LlmException e = failure(transport.postJson(uri(), Map.of(), body("{}")));
        assertEquals(LlmErrorKind.AUTH, e.kind());
        assertEquals(401, e.httpStatus());
    }

    @Test
    void maps429ToRateLimit() {
        for (int i = 0; i < 3; i++) {
            server.enqueue(429, "{}");
        }
        LlmException e = failure(transport.postJson(uri(), Map.of(), body("{}")));
        assertEquals(LlmErrorKind.RATE_LIMIT, e.kind());
        assertEquals(429, e.httpStatus());
    }

    @Test
    void maps400ToBadRequest() {
        server.enqueue(400, "{\"error\":{\"message\":\"bad model\"}}");
        LlmException e = failure(transport.postJson(uri(), Map.of(), body("{}")));
        assertEquals(LlmErrorKind.BAD_REQUEST, e.kind());
        assertEquals(400, e.httpStatus());
        assertTrue(e.getMessage().contains("bad model"));
    }

    @Test
    void maps503ToServer() {
        for (int i = 0; i < 3; i++) {
            server.enqueue(503, "{}");
        }
        LlmException e = failure(transport.postJson(uri(), Map.of(), body("{}")));
        assertEquals(LlmErrorKind.SERVER, e.kind());
        assertEquals(503, e.httpStatus());
    }

    @Test
    void retriesServerErrorsThenSucceeds() throws Exception {
        server.enqueue(503, "{}");
        server.enqueue(503, "{}");
        server.enqueue(200, "{\"ok\":true}");
        JsonObject result = transport.postJson(uri(), Map.of(), body("{}")).get();
        assertTrue(result.get("ok").getAsBoolean());
        assertEquals(3, server.requests().size());
    }

    @Test
    void doesNotRetryAuth() {
        server.enqueue(401, "{}");
        server.enqueue(200, "{\"ok\":true}");
        LlmException e = failure(transport.postJson(uri(), Map.of(), body("{}")));
        assertEquals(LlmErrorKind.AUTH, e.kind());
        assertEquals(1, server.requests().size());
    }

    @Test
    void invalidJsonIsParse() {
        server.enqueue(200, "not json");
        LlmException e = failure(transport.postJson(uri(), Map.of(), body("{}")));
        assertEquals(LlmErrorKind.PARSE, e.kind());
    }

    @Test
    void errorMessageNeverContainsApiKey() {
        server.enqueue(401, "{\"error\":{\"message\":\"Incorrect API key provided: sk-secret-123456\"}}");
        LlmException e =
                failure(transport.postJson(uri(), Map.of("Authorization", "Bearer sk-secret-123456"), body("{}")));
        assertFalse(e.getMessage().contains("sk-secret-123456"));
        assertTrue(e.getMessage().contains("401"));
    }

    @Test
    void utf8RoundTrip() throws Exception {
        server.enqueue(200, "{\"ok\":true}");
        transport.postJson(uri(), Map.of(), body("{\"t\":\"Привет\"}")).get();
        assertEquals("Привет", body(server.requests().get(0).body()).get("t").getAsString());
    }

    @Test
    void getJsonReadsResponse() throws Exception {
        server.enqueue(200, "{\"data\":[]}");
        JsonObject result = transport.getJson(uri(), Map.of("x-api-key", "k")).get();
        assertTrue(result.has("data"));
        assertEquals("GET", server.requests().get(0).method());
        assertEquals("k", server.requests().get(0).header("x-api-key"));
    }
}
