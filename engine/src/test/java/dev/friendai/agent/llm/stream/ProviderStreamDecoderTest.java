package dev.friendai.agent.llm.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProviderStreamDecoderTest {
    private static List<StreamDelta> through(IncrementalSseDecoder sse, StreamDecoder decoder, String text, int chunk) {
        List<StreamDelta> deltas = new ArrayList<>();
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < bytes.length; i += chunk) {
            byte[] part = java.util.Arrays.copyOfRange(bytes, i, Math.min(bytes.length, i + chunk));
            for (StreamEvent event : sse.feed(part)) {
                deltas.addAll(decoder.accept(event));
            }
        }
        for (StreamEvent event : sse.finish()) {
            deltas.addAll(decoder.accept(event));
        }
        deltas.addAll(decoder.finish());
        return deltas;
    }

    @Test
    void openAiTextToolAndDoneSurviveEveryChunkBoundary() {
        String body = "data: {\"choices\":[{\"delta\":{\"content\":\"Hel\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"lo\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"c1\",\"function\":{\"name\":\"mine\",\"arguments\":\"{\\\"block\\\"\"}}]}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\":\\\"stone\\\"}\"}}]}}]}\n\n"
                + "data: {\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":2}}\n\n"
                + "data: [DONE]\n\n";
        for (int chunk : List.of(1, 3, 7, 41)) {
            List<StreamDelta> deltas = through(new IncrementalSseDecoder(), new OpenAiChatStreamDecoder(), body, chunk);
            String text = deltas.stream()
                    .filter(d -> d instanceof StreamDelta.Text)
                    .map(d -> ((StreamDelta.Text) d).text())
                    .reduce("", String::concat);
            assertEquals("Hello", text, "chunk " + chunk);
            List<StreamDelta.ToolCall> calls = deltas.stream()
                    .filter(d -> d instanceof StreamDelta.ToolCall)
                    .map(d -> (StreamDelta.ToolCall) d)
                    .toList();
            assertEquals(2, calls.size());
            assertEquals("c1", calls.getFirst().id());
            assertEquals("mine", calls.getFirst().name());
            assertEquals("{\"block\"", calls.getFirst().arguments());
            assertEquals(":\"stone\"}", calls.get(1).arguments());
            assertTrue(deltas.stream().anyMatch(d -> d instanceof StreamDelta.Usage));
            assertTrue(deltas.stream().anyMatch(d -> d instanceof StreamDelta.Done));
        }
    }

    @Test
    void anthropicTextToolAndStopAreDecoded() {
        String body = "event: content_block_delta\n"
                + "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Hi\"}}\n\n"
                + "event: content_block_start\n"
                + "data: {\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"mine\"}}\n\n"
                + "event: content_block_delta\n"
                + "data: {\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"x\\\":1}\"}}\n\n"
                + "event: message_delta\n"
                + "data: {\"type\":\"message_delta\",\"usage\":{\"output_tokens\":9}}\n\n"
                + "event: message_stop\n"
                + "data: {\"type\":\"message_stop\"}\n\n";
        List<StreamDelta> deltas = through(new IncrementalSseDecoder(), new AnthropicStreamDecoder(), body, 5);
        assertEquals(
                1, deltas.stream().filter(d -> d instanceof StreamDelta.Text).count());
        assertEquals(
                "Hi",
                ((StreamDelta.Text) deltas.stream()
                                .filter(d -> d instanceof StreamDelta.Text)
                                .findFirst()
                                .orElseThrow())
                        .text());
        List<StreamDelta.ToolCall> calls = deltas.stream()
                .filter(d -> d instanceof StreamDelta.ToolCall)
                .map(d -> (StreamDelta.ToolCall) d)
                .toList();
        assertEquals(2, calls.size());
        assertEquals("t1", calls.getFirst().id());
        assertEquals("mine", calls.getFirst().name());
        assertEquals("{\"x\":1}", calls.get(1).arguments());
        assertTrue(deltas.stream().anyMatch(d -> d instanceof StreamDelta.Usage));
        assertTrue(deltas.stream().anyMatch(d -> d instanceof StreamDelta.Done));
    }

    @Test
    void malformedProviderJsonIsAReportedFailureNotAnIncrement() {
        OpenAiChatStreamDecoder openAi = new OpenAiChatStreamDecoder();
        assertTrue(openAi.accept(new StreamEvent("", "{not json")).isEmpty());
        assertTrue(openAi.failed());

        AnthropicStreamDecoder anthropic = new AnthropicStreamDecoder();
        assertTrue(anthropic.accept(new StreamEvent("", "{\"type\":\"error\"}")).isEmpty());
        assertTrue(anthropic.failed());
        assertFalse(openAi.accept(new StreamEvent("", "data")).iterator().hasNext());
    }
}
