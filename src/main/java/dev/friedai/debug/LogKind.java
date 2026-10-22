package dev.friedai.debug;

/** What a {@link LogEntry} records. */
public enum LogKind {
    USER,
    ASSISTANT,
    TOOL_CALL,
    TOOL_RESULT,
    ERROR,
    TASK,
    MANUAL
}
