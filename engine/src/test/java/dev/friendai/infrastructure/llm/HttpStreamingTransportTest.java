package dev.friendai.infrastructure.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.agent.llm.stream.IncrementalSseDecoder;
import dev.friendai.agent.llm.stream.OpenAiChatStreamDecoder;
import dev.friendai.agent.llm.stream.StreamDelta;
import dev.friendai.testutil.MockHttpServer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class HttpStreamingTransportTest {
    @Test
    void chunksArriveInOrderAndDecodeToText() throws Exception {
        try (MockHttpServer server = new MockHttpServer()) {
            server.enqueueStream(
                    200,
                    List.of(
                            "data: {\"choices\":[{\"delta\":{\"content\":\"Hel\"}}]}\n\n",
                            "data: {\"choices\":[{\"delta\":{\"content\":\"lo\"}}]}\n\ndata: [DONE]\n\n"));
            HttpStreamingTransport transport = new HttpStreamingTransport();
            List<byte[]> chunks = new CopyOnWriteArrayList<>();
            transport
                    .postStream(
                            server.baseUri().resolve("/v1/chat"),
                            Map.of("Authorization", "Bearer sk-secret"),
                            "{\"model\":\"m\"}",
                            chunks::add)
                    .get(5, TimeUnit.SECONDS);

            IncrementalSseDecoder sse = new IncrementalSseDecoder();
            OpenAiChatStreamDecoder decoder = new OpenAiChatStreamDecoder();
            List<StreamDelta> deltas = new ArrayList<>();
            for (byte[] chunk : chunks) {
                for (var event : sse.feed(chunk)) {
                    deltas.addAll(decoder.accept(event));
                }
            }
            for (var event : sse.finish()) {
                deltas.addAll(decoder.accept(event));
            }

            String text = deltas.stream()
                    .filter(d -> d instanceof StreamDelta.Text)
                    .map(d -> ((StreamDelta.Text) d).text())
                    .reduce("", String::concat);
            assertEquals("Hello", text);
            assertTrue(deltas.stream().anyMatch(d -> d instanceof StreamDelta.Done));

            var request = server.requests().getFirst();
            assertEquals("Bearer sk-secret", request.header("authorization"));
            assertEquals("{\"model\":\"m\"}", request.body());
            assertFalse(request.body().contains("sk-secret"), "credentials never enter the body");
        }
    }

    @Test
    void aNon2xxStatusFailsTheFuture() {
        try (MockHttpServer server = new MockHttpServer()) {
            server.enqueue(401, "{}");
            HttpStreamingTransport transport = new HttpStreamingTransport();
            ExecutionException error = assertThrows(
                    ExecutionException.class,
                    () -> transport
                            .postStream(
                                    server.baseUri().resolve("/v1/chat"),
                                    Map.of("x-api-key", "secret"),
                                    "{}",
                                    chunk -> {})
                            .get(5, TimeUnit.SECONDS));
            assertTrue(error.getCause().getMessage().contains("401"));
        }
    }

    @Test
    void cancellationAbortsTheSubscription() {
        try (MockHttpServer server = new MockHttpServer()) {
            server.enqueueStreamDelayed(200, List.of("data: a\n\n", "data: b\n\n"), 200);
            HttpStreamingTransport transport = new HttpStreamingTransport();
            var future = transport.postStream(server.baseUri().resolve("/v1/chat"), Map.of(), "{}", chunk -> {});
            assertTrue(future.cancel(true));
            assertTrue(future.isCancelled());
        }
    }

    @Test
    void aConsumerFailureFailsTheFuture() {
        try (MockHttpServer server = new MockHttpServer()) {
            server.enqueueStream(200, List.of("data: x\n\n"));
            HttpStreamingTransport transport = new HttpStreamingTransport();
            ExecutionException error = assertThrows(
                    ExecutionException.class,
                    () -> transport
                            .postStream(server.baseUri().resolve("/v1/chat"), Map.of(), "{}", chunk -> {
                                throw new IllegalStateException("stop");
                            })
                            .get(5, TimeUnit.SECONDS));
            assertEquals("stop", error.getCause().getMessage());
        }
    }
}
