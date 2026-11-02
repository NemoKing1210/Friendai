package dev.friendai.agent.llm.stream;

/**
 * One bounded increment decoded from a provider stream. Text and tool-call fragments are not interchangeable: the turn
 * may speak text early, but a tool call is assembled only and executed after the complete response.
 */
public sealed interface StreamDelta
        permits StreamDelta.Text, StreamDelta.ToolCall, StreamDelta.Usage, StreamDelta.Done {
    record Text(String text) implements StreamDelta {
        public Text {
            text = text == null ? "" : text;
        }
    }

    /** A tool-call fragment: id/name usually arrive once, arguments arrive as partial JSON. */
    record ToolCall(int index, String id, String name, String arguments) implements StreamDelta {
        public ToolCall {
            id = id == null ? "" : id;
            name = name == null ? "" : name;
            arguments = arguments == null ? "" : arguments;
        }
    }

    record Usage(Integer inputTokens, Integer outputTokens) implements StreamDelta {}

    record Done() implements StreamDelta {}
}
