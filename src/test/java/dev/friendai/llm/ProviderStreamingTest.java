package dev.friendai.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.agent.llm.model.LlmResponse;
import dev.friendai.agent.llm.model.Message;
import dev.friendai.agent.llm.model.StopReason;
import dev.friendai.agent.llm.provider.LlmProfile;
import dev.friendai.agent.llm.provider.ProviderRegistry;
import dev.friendai.agent.llm.stream.StreamDelta;
import dev.friendai.infrastructure.llm.HttpStreamingTransport;
import dev.friendai.llm.http.HttpTransport;
import dev.friendai.testutil.MockHttpServer;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** The production gateway must stream through the real protocol and client, not only through a test fake. */
class ProviderStreamingTest {
    private static LlmClient client(MockHttpServer server, HttpClient http) {
        HttpTransport json = new HttpTransport(http, Duration.ofSeconds(5), 0, Duration.ofMillis(10));
        LlmProfile profile = new LlmProfile("openai_compat", "test-model", "key", server.baseUrl(), null, 0.2, 256);
        return new LlmClient(ProviderRegistry.withBuiltins(json), profile, new HttpStreamingTransport(http));
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
}
