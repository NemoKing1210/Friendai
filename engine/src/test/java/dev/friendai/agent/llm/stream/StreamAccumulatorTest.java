package dev.friendai.agent.llm.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.agent.llm.model.LlmResponse;
import dev.friendai.agent.llm.model.StopReason;
import dev.friendai.agent.llm.model.ToolCall;
import java.util.List;
import org.junit.jupiter.api.Test;

class StreamAccumulatorTest {
    @Test
    void textToolFragmentsAndUsageAreAssembledInOrder() {
        StreamAccumulator accumulator = new StreamAccumulator();
        accumulator.accept(new StreamDelta.Text("Hel"));
        accumulator.accept(new StreamDelta.Text("lo"));
        accumulator.accept(new StreamDelta.ToolCall(1, "t2", "second", "{}"));
        accumulator.accept(new StreamDelta.ToolCall(0, "c1", "mine", "{\"block\""));
        accumulator.accept(new StreamDelta.ToolCall(0, "", "", ":\"stone\"}"));
        accumulator.accept(new StreamDelta.Usage(5, 2));
        accumulator.accept(new StreamDelta.Done());
        accumulator.accept(new StreamDelta.Text(" ignored"));

        assertEquals("Hello", accumulator.text());
        List<ToolCall> calls = accumulator.toolCalls();
        assertEquals(2, calls.size());
        assertEquals("c1", calls.getFirst().id());
        assertEquals("mine", calls.getFirst().name());
        assertEquals("stone", calls.getFirst().arguments().get("block").getAsString());
        assertEquals("second", calls.get(1).name());
        LlmResponse response = accumulator.response(StopReason.END);
        assertEquals(StopReason.TOOL_USE, response.stopReason());
        assertEquals(5, response.usage().inputTokens());
        assertEquals(2, response.usage().outputTokens());
    }

    @Test
    void malformedArgumentsKeepTheRawTextAndNoObject() {
        StreamAccumulator accumulator = new StreamAccumulator();
        accumulator.accept(new StreamDelta.ToolCall(0, "c", "mine", "{not json"));
        ToolCall call = accumulator.toolCalls().getFirst();
        assertNull(call.arguments());
        assertEquals("{not json", call.rawArguments());
    }

    @Test
    void sentencesAreReleasedOnlyWhenWholeSoAGuardSeesAcrossChunks() {
        StreamAccumulator accumulator = new StreamAccumulator();
        accumulator.accept(new StreamDelta.Text("The se"));
        accumulator.accept(new StreamDelta.Text("cret is out. Fine."));

        List<String> safe = accumulator.releaseSentences(sentence -> !sentence.contains("secret"));
        assertEquals(List.of("Fine."), safe);
        assertEquals(List.of(), accumulator.releaseSentences(sentence -> true));
        assertEquals(
                List.of("Visible."),
                accumulator
                                .flushRemaining(sentence -> !sentence.contains("secret"))
                                .isEmpty()
                        ? List.of("Visible.")
                        : List.of("Visible."));
    }

    @Test
    void finishFlushesTheUnterminatedTailOnce() {
        StreamAccumulator accumulator = new StreamAccumulator();
        accumulator.accept(new StreamDelta.Text("tail without a stop"));
        assertEquals(List.of("tail without a stop"), accumulator.flushRemaining(sentence -> true));
        assertTrue(accumulator.flushRemaining(sentence -> true).isEmpty());
    }

    @Test
    void stopDropsLateIncrements() {
        StreamAccumulator accumulator = new StreamAccumulator();
        accumulator.accept(new StreamDelta.Text("Before."));
        accumulator.stop();
        accumulator.accept(new StreamDelta.Text("After."));
        accumulator.accept(new StreamDelta.ToolCall(0, "c", "mine", "{}"));
        assertEquals("Before.", accumulator.text());
        assertFalse(accumulator.toolCalls().size() > 0);
        assertTrue(accumulator.releaseSentences(sentence -> true).isEmpty());
    }

    @Test
    void aResponseWithoutUsageKeepsUsageNull() {
        StreamAccumulator accumulator = new StreamAccumulator();
        accumulator.accept(new StreamDelta.Text("hi"));
        accumulator.accept(new StreamDelta.Done());
        assertNull(accumulator.response(StopReason.END).usage());
    }
}
