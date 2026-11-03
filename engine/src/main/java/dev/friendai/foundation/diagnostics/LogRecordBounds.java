package dev.friendai.foundation.diagnostics;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Applies the field limits in {@link LogLimits} to a record after redaction and before any sink sees it.
 *
 * <p>The pipeline calls this even when a producer bypassed {@link LogEventData}, so the ring and every file sink hold
 * the same bounded values. Identity keys ({@code trace}, {@code execution}, {@code span}, {@code parentSpan}) are kept
 * ahead of optional facts; when anything is dropped or shortened the data map carries
 * {@code truncated = <count>}. A record already inside every limit is returned unchanged.
 */
public final class LogRecordBounds {
    private static final String ELLIPSIS = "…";

    private LogRecordBounds() {}

    public static LogRecord apply(LogRecord record) {
        if (record == null) return null;
        String event = cap(record.event(), LogLimits.MAX_EVENT_CHARS);
        String session = cap(record.session(), LogLimits.MAX_SESSION_CHARS);
        String toolCall = cap(record.toolCall(), LogLimits.MAX_TOOL_CALL_CHARS);
        String message = cap(record.message(), LogLimits.MAX_MESSAGE_CHARS);
        LogError error = boundError(record.error());
        Map<String, String> data = boundData(record.data());
        if (event == record.event()
                && session == record.session()
                && toolCall == record.toolCall()
                && message == record.message()
                && error == record.error()
                && data == record.data()) {
            return record;
        }
        return new LogRecord(
                record.seq(),
                record.timeMillis(),
                record.level(),
                record.category(),
                record.kind(),
                event,
                record.owner(),
                record.companion(),
                session,
                record.turn(),
                record.plan(),
                record.job(),
                record.task(),
                toolCall,
                record.cause(),
                record.durationMs(),
                message,
                data,
                error);
    }

    private static Map<String, String> boundData(Map<String, String> data) {
        if (data == null || data.isEmpty() || fits(data)) {
            return data;
        }
        Map<String, String> bounded = new LinkedHashMap<>();
        int affected = 0;
        for (String key : LogLimits.IDENTITY_KEYS) {
            String value = data.get(key);
            if (value == null) continue;
            String capped = cap(value, LogLimits.MAX_VALUE_CHARS);
            if (capped != value) affected++;
            bounded.put(key, capped);
        }
        String marker = data.get(LogEventData.TRUNCATED_KEY);
        if (marker != null) {
            bounded.put(LogEventData.TRUNCATED_KEY, cap(marker, LogLimits.MAX_VALUE_CHARS));
        }
        int capacity = LogLimits.MAX_DATA_KEYS - 1;
        for (Map.Entry<String, String> entry : data.entrySet()) {
            String key = entry.getKey();
            if (key == null || LogLimits.isIdentityKey(key) || LogEventData.TRUNCATED_KEY.equals(key)) {
                continue;
            }
            if (bounded.size() >= capacity) {
                affected++;
                continue;
            }
            String cappedKey = cap(key, LogLimits.MAX_KEY_CHARS);
            String value = entry.getValue() == null ? "" : entry.getValue();
            String cappedValue = cap(value, LogLimits.MAX_VALUE_CHARS);
            if (cappedKey != key || cappedValue != value || bounded.containsKey(cappedKey)) {
                affected++;
            }
            if (bounded.containsKey(cappedKey)) {
                continue;
            }
            bounded.put(cappedKey, cappedValue);
        }
        if (affected > 0) {
            bounded.put(LogEventData.TRUNCATED_KEY, Long.toString(markerValue(marker) + affected));
        }
        return java.util.Collections.unmodifiableMap(bounded);
    }

    private static boolean fits(Map<String, String> data) {
        if (data.size() > LogLimits.MAX_DATA_KEYS) return false;
        for (Map.Entry<String, String> entry : data.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null || key.length() > LogLimits.MAX_KEY_CHARS) return false;
            if (value == null || value.length() > LogLimits.MAX_VALUE_CHARS) return false;
        }
        return true;
    }

    private static LogError boundError(LogError error) {
        if (error == null) return null;
        String type = cap(error.type(), LogLimits.MAX_ERROR_TYPE_CHARS);
        String message = cap(error.message(), LogLimits.MAX_MESSAGE_CHARS);
        String stack = cap(error.stack(), LogLimits.MAX_STACK_CHARS);
        int frames = Math.min(error.frames(), LogLimits.MAX_STACK_FRAMES);
        if (type == error.type() && message == error.message() && stack == error.stack() && frames == error.frames()) {
            return error;
        }
        return new LogError(type, message, stack, frames);
    }

    private static long markerValue(String marker) {
        if (marker == null || marker.isBlank()) return 0L;
        try {
            return Math.max(0L, Long.parseLong(marker.strip()));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private static String cap(String text, int max) {
        String value = text == null ? "" : text;
        if (value.length() <= max) return value;
        return max <= ELLIPSIS.length() ? ELLIPSIS : value.substring(0, max - ELLIPSIS.length()) + ELLIPSIS;
    }
}
