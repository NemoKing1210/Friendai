package dev.friendai.agent.llm.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class GeminiOllamaStreamDecoderTest {
    @Test
    void geminiTextFunctionCallFinishAndUsageAreDecoded() {
        GeminiStreamDecoder decoder = new GeminiStreamDecoder();
        List<StreamDelta> deltas = new java.util.ArrayList<>();
        deltas.addAll(decoder.accept(
                new StreamEvent("", "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Hi\"}]}}]}")));
        deltas.addAll(
                decoder.accept(
                        new StreamEvent(
                                "",
                                "{\"candidates\":[{\"content\":{\"parts\":[{\"functionCall\":{\"name\":\"mine\",\"args\":{\"block\":\"stone\"}}}]},\"finishReason\":\"STOP\"}],\"usageMetadata\":{\"promptTokenCount\":5,\"candidatesTokenCount\":2}}")));

        assertEquals(
                "Hi",
                ((StreamDelta.Text) deltas.stream()
                                .filter(d -> d instanceof StreamDelta.Text)
                                .findFirst()
                                .orElseThrow())
                        .text());
        StreamDelta.ToolCall call = (StreamDelta.ToolCall) deltas.stream()
                .filter(d -> d instanceof StreamDelta.ToolCall)
                .findFirst()
                .orElseThrow();
        assertEquals("mine", call.name());
        assertTrue(call.arguments().contains("stone"));
        assertTrue(deltas.stream().anyMatch(d -> d instanceof StreamDelta.Done));
        assertTrue(deltas.stream().anyMatch(d -> d instanceof StreamDelta.Usage));
    }

    @Test
    void ollamaNdjsonTextDoneAndUsageAreDecoded() {
        OllamaStreamDecoder decoder = new OllamaStreamDecoder();
        List<StreamDelta> deltas = new java.util.ArrayList<>();
        deltas.addAll(decoder.accept(new StreamEvent("", "{\"message\":{\"content\":\"Hel\"},\"done\":false}")));
        deltas.addAll(decoder.accept(new StreamEvent("", "{\"message\":{\"content\":\"lo\"},\"done\":false}")));
        deltas.addAll(decoder.accept(new StreamEvent(
                "", "{\"message\":{\"content\":\"\"},\"done\":true,\"prompt_eval_count\":3,\"eval_count\":2}")));

        String text = deltas.stream()
                .filter(d -> d instanceof StreamDelta.Text)
                .map(d -> ((StreamDelta.Text) d).text())
                .reduce("", String::concat);
        assertEquals("Hello", text);
        assertTrue(deltas.stream().anyMatch(d -> d instanceof StreamDelta.Done));
        assertTrue(deltas.stream().anyMatch(d -> d instanceof StreamDelta.Usage));
    }

    @Test
    void malformedProviderDataIsAReportedFailure() {
        GeminiStreamDecoder gemini = new GeminiStreamDecoder();
        assertTrue(gemini.accept(new StreamEvent("", "not json")).isEmpty());
        assertTrue(gemini.failed());

        OllamaStreamDecoder ollama = new OllamaStreamDecoder();
        assertTrue(ollama.accept(new StreamEvent("", "{\"broken\":")).isEmpty());
        assertTrue(ollama.failed());
    }
}
