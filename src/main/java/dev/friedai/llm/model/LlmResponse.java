package dev.friedai.llm.model;

import java.util.List;

/** @param text the assistant text, "" if none */
public record LlmResponse(String text, List<ToolCall> toolCalls, StopReason stopReason, Usage usage) {
    public LlmResponse {
        text = text == null ? "" : text;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }
}
