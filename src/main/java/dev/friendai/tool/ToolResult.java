package dev.friendai.tool;

/** The outcome of a tool call. {@code content} is English and addressed to the LLM. */
public record ToolResult(String content, boolean error) {
    public static ToolResult ok(String content) {
        return new ToolResult(content, false);
    }

    public static ToolResult error(String content) {
        return new ToolResult(content, true);
    }
}
