package dev.friendai.foundation.diagnostics;

import java.util.Map;
import java.util.UUID;

/**
 * One structured fact in the durable log. Producers build a draft with {@code seq = 0}; the pipeline assigns the
 * sequence and fills a missing timestamp before any sink sees it.
 */
public record LogRecord(
        long seq,
        long timeMillis,
        LogLevel level,
        LogCategory category,
        LogKind kind,
        String event,
        UUID owner,
        UUID companion,
        String session,
        long turn,
        long plan,
        long job,
        long task,
        String toolCall,
        long cause,
        long durationMs,
        String message,
        Map<String, String> data,
        LogError error) {
    public LogRecord {
        event = event == null ? "" : event;
        session = session == null ? "" : session;
        toolCall = toolCall == null ? "" : toolCall;
        message = message == null ? "" : message;
        data = data == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(data));
    }
}
