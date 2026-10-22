package dev.friedai.llm.model;

import java.util.List;

public record Message(Role role, String text, List<ToolCall> toolCalls, String toolCallId, String toolName) {
    public Message {
        text = text == null ? "" : text;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public static Message user(String text) {
        return new Message(Role.USER, text, List.of(), null, null);
    }

    public static Message assistant(String text, List<ToolCall> calls) {
        return new Message(Role.ASSISTANT, text, calls, null, null);
    }

    public static Message toolResult(String toolCallId, String toolName, String content) {
        return new Message(Role.TOOL, content, List.of(), toolCallId, toolName);
    }
}
