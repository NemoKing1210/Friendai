package dev.friendai.agent.llm.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class OpenAiResponsesStreamDecoderTest {
    @Test
    void textFunctionCallAndCompletionAreDecoded() {
        OpenAiResponsesStreamDecoder decoder = new OpenAiResponsesStreamDecoder();
        List<StreamDelta> deltas = new ArrayList<>();
        deltas.addAll(decoder.accept(new StreamEvent(
                "response.output_text.delta", "{\"type\":\"response.output_text.delta\",\"delta\":\"Hi\"}")));
        deltas.addAll(
                decoder.accept(
                        new StreamEvent(
                                "response.output_item.added",
                                "{\"type\":\"response.output_item.added\",\"output_index\":1,\"item\":{\"type\":\"function_call\",\"call_id\":\"c1\",\"name\":\"mine\"}}")));
        deltas.addAll(
                decoder.accept(
                        new StreamEvent(
                                "response.function_call_arguments.delta",
                                "{\"type\":\"response.function_call_arguments.delta\",\"output_index\":1,\"delta\":\"{\\\"block\\\":\\\"stone\\\"}\"}")));
        deltas.addAll(
                decoder.accept(
                        new StreamEvent(
                                "response.completed",
                                "{\"type\":\"response.completed\",\"response\":{\"usage\":{\"input_tokens\":5,\"output_tokens\":2}}}")));

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
        assertEquals("c1", calls.getFirst().id());
        assertEquals("mine", calls.getFirst().name());
        assertTrue(calls.get(1).arguments().contains("stone"));
        assertTrue(deltas.stream().anyMatch(d -> d instanceof StreamDelta.Done));
        assertTrue(deltas.stream().anyMatch(d -> d instanceof StreamDelta.Usage));
    }

    @Test
    void malformedOrErrorEventsFailWithoutIncrements() {
        OpenAiResponsesStreamDecoder decoder = new OpenAiResponsesStreamDecoder();
        assertTrue(decoder.accept(new StreamEvent("", "{broken")).isEmpty());
        assertTrue(decoder.failed());

        OpenAiResponsesStreamDecoder error = new OpenAiResponsesStreamDecoder();
        assertTrue(
                error.accept(new StreamEvent("error", "{\"type\":\"error\"}")).isEmpty());
        assertTrue(error.failed());
    }
}
