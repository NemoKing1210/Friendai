package dev.friendai.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import dev.friendai.agent.llm.JsonHttpTransport;
import dev.friendai.agent.llm.RequestAttempt;
import dev.friendai.agent.llm.RequestTelemetry;
import dev.friendai.agent.llm.StreamingHttpTransport;
import dev.friendai.agent.llm.model.LlmErrorKind;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The production gateway must stream through the real protocol and client, not only through a test fake. */
class ProviderStreamingTest {
    private static LlmClient client(MockHttpServer server, HttpClient http) {
        return client(server, http, Duration.ofSeconds(5));
    }

    private static LlmClient client(MockHttpServer server, HttpClient http, Duration timeout) {
        HttpTransport json = new HttpTransport(http, timeout, 0, Duration.ofMillis(10));
        LlmProfile profile = new LlmProfile("openai_compat", "test-model", "key", server.baseUrl(), null, 0.2, 256);
        return new LlmClient(ProviderRegistry.withBuiltins(json), profile, new HttpStreamingTransport(http, timeout));
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
    void aStreamReportsOnlyOneFirstUsableDeltaAndTerminalTiming() throws Exception {
        try (MockHttpServer server = new MockHttpServer()) {
            server.enqueueStream(
                    200,
                    List.of(
                            ": keepalive\n\n",
                            "data: {\"choices\":[{\"delta\":{\"content\":\"First\"}}]}\n\n",
                            "data: {\"choices\":[{\"delta\":{\"content\":\" Second\"}}]}\n\n",
                            "data: [DONE]\n\n"));
            LlmClient client = client(server, HttpClient.newHttpClient());
            List<RequestAttempt> attempts = new CopyOnWriteArrayList<>();
            RequestTelemetry telemetry = new RequestTelemetry(31, 4, 2, attempts::add);

            assertEquals(
                    "First Second",
                    client.completeStreaming("system", List.of(Message.user("hi")), List.of(), delta -> {}, telemetry)
                            .get(5, TimeUnit.SECONDS)
                            .text());
            assertEquals(
                    List.of("start", "first_delta", "finish"),
                    attempts.stream().map(RequestAttempt::stage).toList());
            assertEquals(200, attempts.getLast().status());
            assertTrue(attempts.get(1).durationMs() >= 0);
            assertTrue(attempts.getLast().durationMs() >= attempts.get(1).durationMs());
        }
    }

    @Test
    void aTimeoutBeforeContentSendsNoFallbackRequest() {
        FakeStream stream = new FakeStream();
        FakeJson json = new FakeJson();
        LlmClient client = fakeClient(stream, json);
        var reply = client.completeStreaming("system", List.of(Message.user("hi")), List.of(), delta -> {});
        stream.body.completeExceptionally(new LlmException(LlmErrorKind.TIMEOUT, "request timed out"));

        ExecutionException error = assertThrows(ExecutionException.class, () -> reply.get(1, TimeUnit.SECONDS));
        assertEquals(LlmErrorKind.TIMEOUT, ((LlmException) error.getCause()).kind());
        assertTrue(json.posted.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aStalledOpenStreamTimesOutWithoutHoldingTheNextRequest(boolean withSpeech) throws Exception {
        try (MockHttpServer server = new MockHttpServer()) {
            String textFrame = "data: {\"choices\":[{\"delta\":{\"content\":\"Hello there.\"}}]}\n\n";
            server.enqueueStreamDelayed(
                    200, List.of(withSpeech ? textFrame : ": keepalive\n\n", textFrame, "data: [DONE]\n\n"), 3000);
            server.enqueueStream(200, List.of(textFrame, "data: [DONE]\n\n"));
            LlmClient client = client(server, HttpClient.newHttpClient(), Duration.ofSeconds(1));
            List<RequestAttempt> attempts = new CopyOnWriteArrayList<>();
            RequestTelemetry telemetry = new RequestTelemetry(31, 4, 2, attempts::add);
            List<StreamDelta> deltas = new CopyOnWriteArrayList<>();
            List<Message> messages = List.of(Message.user("hi"));

            var reply = client.completeStreaming("system", messages, List.of(), deltas::add, telemetry);
            ExecutionException error = assertThrows(ExecutionException.class, () -> reply.get(2, TimeUnit.SECONDS));
            assertEquals(LlmErrorKind.TIMEOUT, ((LlmException) error.getCause()).kind());
            assertEquals(withSpeech ? List.of(new StreamDelta.Text("Hello there.")) : List.of(), deltas);
            assertEquals(
                    withSpeech ? List.of("start", "first_delta", "finish") : List.of("start", "finish"),
                    attempts.stream().map(RequestAttempt::stage).toList());
            assertEquals(200, attempts.getLast().status());
            assertEquals("timeout", attempts.getLast().errorKind());
            assertEquals(1, server.requests().size());

            assertEquals(
                    "Hello there.",
                    client.completeStreaming("system", messages, List.of(), delta -> {})
                            .get(2, TimeUnit.SECONDS)
                            .text());
            assertEquals(2, server.requests().size());
        }
    }

    @Test
    void aTimeoutWaitingForHeadersSendsNoFallbackRequest() throws Exception {
        try (MockHttpServer server = new MockHttpServer()) {
            server.enqueueDelayed(200, "{}".getBytes(StandardCharsets.UTF_8), 3000);
            LlmClient client = client(server, HttpClient.newHttpClient(), Duration.ofSeconds(1));
            List<RequestAttempt> attempts = new CopyOnWriteArrayList<>();
            RequestTelemetry telemetry = new RequestTelemetry(31, 4, 2, attempts::add);

            var reply =
                    client.completeStreaming("system", List.of(Message.user("hi")), List.of(), delta -> {}, telemetry);
            ExecutionException error = assertThrows(ExecutionException.class, () -> reply.get(2, TimeUnit.SECONDS));
            assertEquals(LlmErrorKind.TIMEOUT, ((LlmException) error.getCause()).kind());
            assertEquals(
                    List.of("start", "finish"),
                    attempts.stream().map(RequestAttempt::stage).toList());
            assertEquals(0, attempts.getLast().status());
            assertEquals("timeout", attempts.getLast().errorKind());
            assertEquals(1, server.requests().size());
        }
    }

    @Test
    void cancellingAnOpenStreamRecordsOneCancellationAndLeavesTheNextRequestUsable() throws Exception {
        try (MockHttpServer server = new MockHttpServer()) {
            String textFrame = "data: {\"choices\":[{\"delta\":{\"content\":\"First.\"}}]}\n\n";
            server.enqueueStreamDelayed(200, List.of(textFrame, textFrame, "data: [DONE]\n\n"), 3000);
            server.enqueueStream(200, List.of(textFrame, "data: [DONE]\n\n"));
            LlmClient client = client(server, HttpClient.newHttpClient());
            List<RequestAttempt> attempts = new CopyOnWriteArrayList<>();
            CountDownLatch firstDelta = new CountDownLatch(1);
            RequestTelemetry telemetry = new RequestTelemetry(31, 4, 2, attempt -> {
                attempts.add(attempt);
                if (RequestAttempt.FIRST_DELTA.equals(attempt.stage())) firstDelta.countDown();
            });
            List<StreamDelta> deltas = new CopyOnWriteArrayList<>();
            List<Message> messages = List.of(Message.user("hi"));
            var reply = client.completeStreaming("system", messages, List.of(), deltas::add, telemetry);

            assertTrue(firstDelta.await(2, TimeUnit.SECONDS));
            assertTrue(reply.cancel(true));
            assertEquals(
                    List.of("start", "first_delta", "finish"),
                    attempts.stream().map(RequestAttempt::stage).toList());
            assertEquals("cancelled", attempts.getLast().errorKind());
            assertEquals(
                    "First.",
                    client.completeStreaming("system", messages, List.of(), delta -> {})
                            .get(2, TimeUnit.SECONDS)
                            .text());
            assertEquals(List.of(new StreamDelta.Text("First.")), deltas);
            assertEquals(2, server.requests().size());
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
