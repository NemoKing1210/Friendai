package dev.friendai.foundation.diagnostics;

/** What a {@link LogEntry} records. */
public enum LogKind {
    USER,
    ASSISTANT,
    TOOL_CALL,
    TOOL_RESULT,
    ERROR,
    TASK,
    MANUAL,
    PLANNER,
    REQUEST,
    RESPONSE
}
