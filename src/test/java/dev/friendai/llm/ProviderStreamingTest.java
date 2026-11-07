package dev.friendai.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import dev.friendai.agent.llm.JsonHttpTransport;
import dev.friendai.agent.llm.StreamingHttpTransport;
import dev.friendai.agent.llm.model.LlmException;
import dev.friendai.agent.llm.model.LlmResponse;
import dev.friendai.agent.llm.model.Message;
import dev.friendai.agent.llm.model.StopReason;
import dev.friendai.agent.llm.provider.LlmProfile;
import dev.friendai.agent.llm.provider.ProviderRegistry;
import dev.friendai.agent.llm.stream.StreamDelta;
import dev.friendai.infrastructure.llm.HttpStreamingTransport;
import dev.friendai.llm.http.HttpTransport;
import dev.friendai.testutil.MockHttpServer;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** The production gateway must stream through the real protocol and client, not only through a test fake. */
class ProviderStreamingTest {
    private static LlmClient client(MockHttpServer server, HttpClient http) {
        HttpTransport json = new HttpTransport(http, Duration.ofSeconds(5), 0, Duration.ofMillis(10));
        LlmProfile profile = new LlmProfile("openai_compat", "test-model", "key", server.baseUrl(), null, 0.2, 256);
        return new LlmClient(ProviderRegistry.withBuiltins(json), profile, new HttpStreamingTransport(http));
    }

    /** A stream a test drives frame by frame: no socket, so the order of events is exact. */
    private static final class FakeStream implements StreamingHttpTransport {
        final CompletableFuture<Void> body = new CompletableFuture<>();
        private Consumer<byte[]> chunks;

        @Override
        public CompletableFuture<Void> postStream(
                URI uri, Map<String, String> headers, String body, Consumer<byte[]> onChunk) {
            this.chunks = onChunk;
            return this.body;
        }

        void frame(String data) {
            chunks.accept(("data: " + data + "\n\n").getBytes(StandardCharsets.UTF_8));
        }
    }

    /** The ordinary request path: records each call and holds its answer open. */
    private static final class FakeJson implements JsonHttpTransport {
        final List<JsonObject> posted = new CopyOnWriteArrayList<>();
        final AtomicReference<CompletableFuture<JsonObject>> pending = new AtomicReference<>();

        @Override
        public CompletableFuture<JsonObject> postJson(URI uri, Map<String, String> headers, JsonObject body) {
            posted.add(body);
            CompletableFuture<JsonObject> answer = new CompletableFuture<>();
            pending.set(answer);
            return answer;
        }

        @Override
        public CompletableFuture<JsonObject> getJson(URI uri, Map<String, String> headers) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException("the stream test reads no models"));
        }
    }

    private static LlmClient fakeClient(StreamingHttpTransport streaming, JsonHttpTransport json) {
        LlmProfile profile = new LlmProfile("openai_compat", "test-model", "key", "http://127.0.0.1:1", null, 0.2, 256);
        return new LlmClient(ProviderRegistry.withBuiltins(json), profile, streaming);
    }

    @Test
    void aSentenceArrivesBeforeTheResponseCompletes() throws Exception {
        try (MockHttpServer server = new MockHttpServer()) {
            server.enqueueStreamDelayed(
                    200,
                    List.of(
                            "data: {\"choices\":[{\"delta\":{\"content\":\"Hel\"}}]}\n\n",
                            "data: {\"choices\":[{\"delta\":{\"content\":\"lo\"},\"finish_reason\":\"stop\"}]}\n\n",
                            "data: [DONE]\n\n"),
                    200);
            LlmClient client = client(server, HttpClient.newHttpClient());
            CountDownLatch first = new CountDownLatch(1);
            List<String> deltas = new CopyOnWriteArrayList<>();

            CompletableFuture<LlmResponse> reply =
                    client.completeStreaming("system", List.of(Message.user("hi")), List.of(), delta -> {
                        if (delta instanceof StreamDelta.Text text) {
                            deltas.add(text.text());
                            first.countDown();
                        }
                    });

            assertTrue(first.await(10, TimeUnit.SECONDS), "no delta arrived: " + deltas);
            assertFalse(reply.isDone(), "the response must still be open when the first sentence arrives");
            LlmResponse done = reply.get(10, TimeUnit.SECONDS);
            assertEquals("Hello", done.text());
            assertEquals(StopReason.END, done.stopReason());
            assertEquals(1, server.requests().size());
            assertTrue(server.requests().get(0).body().contains("\"stream\":true"), "the request must be streamed");
        }
    }

    @Test
    void aRefusedStreamFallsBackToOneOrdinaryRequest() throws Exception {
        try (MockHttpServer server = new MockHttpServer()) {
            server.enqueue(500, "{}");
            server.enqueue(
                    200,
                    "{\"choices\":[{\"message\":{\"content\":\"fallback\"},\"finish_reason\":\"stop\"}],"
                            + "\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2}}");
            LlmClient client = client(server, HttpClient.newHttpClient());

            LlmResponse reply = client.completeStreaming("system", List.of(Message.user("hi")), List.of(), delta -> {})
                    .get(10, TimeUnit.SECONDS);

            assertEquals("fallback", reply.text());
            assertEquals(2, server.requests().size(), "the stream, then one ordinary request");
            assertFalse(server.requests().get(1).body().contains("\"stream\":true"));
        }
    }

    @Test
    void aCancelledStreamSendsNoFallbackRequest() {
        FakeStream stream = new FakeStream();
        FakeJson json = new FakeJson();
        LlmClient client = fakeClient(stream, json);

        CompletableFuture<LlmResponse> reply =
                client.completeStreaming("system", List.of(Message.user("hi")), List.of(), delta -> {});
        stream.frame("{\"choices\":[{\"delta\":{\"content\":\"Hel\"}}]}");

        reply.cancel(true);
        stream.body.complete(null);

        assertTrue(reply.isCancelled());
        assertTrue(stream.body.isCancelled(), "the active body is cancelled with the request");
        assertTrue(json.posted.isEmpty(), "a cancelled request sends no fallback: " + json.posted.size());
    }

    @Test
    void aThrowingTerminalConsumerSettlesTheFuture() {
        FakeStream stream = new FakeStream();
        FakeJson json = new FakeJson();
        LlmClient client = fakeClient(stream, json);
        RuntimeException broken = new IllegalStateException("the terminal consumer died");

        CompletableFuture<LlmResponse> reply =
                client.completeStreaming("system", List.of(Message.user("hi")), List.of(), delta -> {
                    if (delta instanceof StreamDelta.Done) throw broken;
                });
        stream.frame("{\"choices\":[{\"delta\":{\"content\":\"Hi\"}}]}");
        stream.frame("[DONE]");
        stream.body.complete(null);

        ExecutionException error = assertThrows(ExecutionException.class, () -> reply.get(5, TimeUnit.SECONDS));
        assertTrue(error.getCause() == broken, "a faulty consumer still settles the request");
    }

    @Test
    void aStreamThatEndsWithoutATerminalFrameIsIncomplete() {
        try (MockHttpServer server = new MockHttpServer()) {
            server.enqueueStream(
                    200,
                    List.of(
                            "data: {\"choices\":[{\"delta\":{\"content\":\"Hel\"}}]}\n\n",
                            "data: {\"choices\":[{\"delta\":{\"content\":\"lo\"}}]}\n\n"));
            LlmClient client = client(server, HttpClient.newHttpClient());

            ExecutionException error = assertThrows(
                    ExecutionException.class,
                    () -> client.completeStreaming("system", List.of(Message.user("hi")), List.of(), delta -> {})
                            .get(10, TimeUnit.SECONDS));

            assertTrue(error.getCause() instanceof LlmException, String.valueOf(error.getCause()));
            assertEquals(1, server.requests().size(), "a half-streamed answer is not replaced by another one");
        }
    }

    @Test
    void aFinishReasonWithoutDoneFrameCompletes() throws Exception {
        try (MockHttpServer server = new MockHttpServer()) {
            server.enqueueStream(
                    200,
                    List.of(
                            "data: {\"choices\":[{\"delta\":{\"content\":\"Hel\"}}]}\n\n",
                            "data: {\"choices\":[{\"delta\":{\"content\":\"lo\"},\"finish_reason\":\"stop\"}]}\n\n"));
            LlmClient client = client(server, HttpClient.newHttpClient());

            LlmResponse reply = client.completeStreaming("system", List.of(Message.user("hi")), List.of(), delta -> {})
                    .get(10, TimeUnit.SECONDS);

            assertEquals("Hello", reply.text());
            assertEquals(StopReason.END, reply.stopReason());
            assertEquals(1, server.requests().size());
        }
    }

    @Test
    void aCancelledRequestStartsNoFallbackAfterALateTransportFailure() {
        FakeStream stream = new FakeStream();
        FakeJson json = new FakeJson();
        LlmClient client = fakeClient(stream, json);

        CompletableFuture<LlmResponse> reply =
                client.completeStreaming("system", List.of(Message.user("hi")), List.of(), delta -> {});
        reply.cancel(true);
        stream.body.completeExceptionally(new IOException("refused after the stop"));

        assertTrue(reply.isCancelled());
        assertTrue(stream.body.isCancelled(), "the active body is cancelled with the request");
        assertTrue(json.posted.isEmpty(), "a cancelled request starts no fallback: " + json.posted.size());
    }

    @Test
    void aTransportFailureAfterATextDeltaIsIncomplete() {
        FakeStream stream = new FakeStream();
        FakeJson json = new FakeJson();
        LlmClient client = fakeClient(stream, json);

        CompletableFuture<LlmResponse> reply =
                client.completeStreaming("system", List.of(Message.user("hi")), List.of(), delta -> {});
        stream.frame("{\"choices\":[{\"delta\":{\"content\":\"Hel\"}}]}");
        stream.body.completeExceptionally(new IOException("connection reset"));

        ExecutionException error = assertThrows(ExecutionException.class, () -> reply.get(5, TimeUnit.SECONDS));
        assertTrue(error.getCause() instanceof LlmException, String.valueOf(error.getCause()));
        assertTrue(json.posted.isEmpty(), "a partial answer is not replaced by a second one");
    }
}
