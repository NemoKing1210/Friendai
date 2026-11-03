package dev.friendai.foundation.diagnostics;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The diagnostic identity an event belongs to: which trace, model turn and execution produced it, and which nested
 * span or parent it descends from. Immutable, so a worker or deferred callback can hold it without reading live state.
 *
 * <p>Only {@code trace}, {@code execution}, {@code span} and {@code parentSpan} become record {@code data} keys:
 * turn, plan, job, task, tool call and cause already have top-level fields in schema 1. A zero identifier means
 * "unknown" and is omitted rather than written as a forged zero.
 */
public record LogContext(
        String trace,
        long turn,
        long plan,
        long job,
        long task,
        long execution,
        long span,
        long parentSpan,
        String toolCall,
        long cause) {

    /** A context with no diagnostic identity; producers that predate this type keep working. */
    public static final LogContext NONE = new LogContext("", 0L, 0L, 0L, 0L, 0L, 0L, 0L, "", 0L);

    public LogContext {
        trace = bound(trace);
        toolCall = toolCall == null ? "" : toolCall;
    }

    public LogContext withCause(long cause) {
        return new LogContext(trace, turn, plan, job, task, execution, span, parentSpan, toolCall, cause);
    }

    /** Attaches the execution that owns the event, keeping the trace, turn and tool call already captured. */
    public LogContext withExecution(long execution, long task, long plan, long job) {
        return new LogContext(trace, turn, plan, job, task, execution, span, parentSpan, toolCall, cause);
    }

    /** Attaches a nested span and its parent, plus an explicit cause sequence. */
    public LogContext withSpan(long span, long parentSpan, long cause) {
        return new LogContext(trace, turn, plan, job, task, execution, span, parentSpan, toolCall, cause);
    }

    /** The identity keys this context contributes to {@code LogRecord.data()}, in a stable order. */
    public Map<String, String> data() {
        Map<String, String> data = new LinkedHashMap<>();
        if (!trace.isEmpty()) {
            data.put("trace", trace);
        }
        if (execution > 0L) {
            data.put("execution", Long.toString(execution));
        }
        if (span > 0L) {
            data.put("span", Long.toString(span));
        }
        if (parentSpan > 0L) {
            data.put("parentSpan", Long.toString(parentSpan));
        }
        return Collections.unmodifiableMap(data);
    }

    private static String bound(String trace) {
        if (trace == null || trace.isEmpty()) return "";
        String value = trace.strip();
        if (value.length() <= LogLimits.MAX_TRACE_CHARS) return value;
        return value.substring(0, LogLimits.MAX_TRACE_CHARS - 1) + "…";
    }
}
