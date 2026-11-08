package dev.friendai.execution.log;

import dev.friendai.foundation.diagnostics.LogCategory;
import dev.friendai.foundation.diagnostics.LogContext;
import dev.friendai.foundation.diagnostics.LogEvents;
import dev.friendai.foundation.diagnostics.LogKind;
import dev.friendai.foundation.diagnostics.LogLevel;
import dev.friendai.foundation.diagnostics.LogRecord;
import dev.friendai.task.TaskPhase;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Builds the durable task, plan and queue records without touching the world. */
public final class TaskLogEvents {
    private TaskLogEvents() {}

    private static LogRecord record(
            LogLevel level,
            LogCategory category,
            String event,
            UUID owner,
            UUID companion,
            String session,
            long task,
            long plan,
            long job,
            String tool,
            String message,
            Map<String, String> data) {
        return new LogRecord(
                0L,
                0L,
                level,
                category,
                LogKind.TASK,
                event,
                owner,
                companion,
                session,
                0L,
                plan,
                job,
                task,
                tool,
                0L,
                -1L,
                message,
                data,
                null);
    }

    /** A task activation start; its parent is the invocation captured in the context. */
    public static TaskLogEvent startEvent(LogContext context, String tool) {
        return startEvent(context, tool, Map.of());
    }

    /** A start that names where the activation came from: a model call, a command, autonomy or a restore. */
    public static TaskLogEvent startEvent(LogContext context, String tool, Map<String, String> facts) {
        requireTool(tool);
        return new TaskLogEvent(
                context, TaskLogEvent.Parent.INVOCATION, LogEvents.TASK_START, tool, "started", -1L, facts);
    }

    /** A graceful terminal; its parent is the execution start resolved by {@link TaskLogCorrelation}. */
    public static TaskLogEvent finishEvent(LogContext context, String tool, String status) {
        return finishEvent(context, tool, status, -1L, Map.of());
    }

    /** A terminal carrying the measured activation time and its counters. */
    public static TaskLogEvent finishEvent(
            LogContext context, String tool, String status, long durationMs, Map<String, String> facts) {
        requireTool(tool);
        return new TaskLogEvent(
                context,
                TaskLogEvent.Parent.EXECUTION,
                LogEvents.TASK_FINISH,
                tool,
                status == null ? "unknown" : status,
                durationMs,
                facts);
    }

    /** A bounded phase fact; a repeated PROGRESS is already throttled by the caller. */
    public static TaskLogEvent phaseEvent(
            LogContext context, String tool, TaskPhase phase, int percent, String reason, String detail) {
        return phaseEvent(context, tool, phase, percent, reason, detail, Map.of());
    }

    /** A phase fact with caller metadata; canonical lifecycle fields always win. */
    public static TaskLogEvent phaseEvent(
            LogContext context,
            String tool,
            TaskPhase phase,
            int percent,
            String reason,
            String detail,
            Map<String, String> extraFacts) {
        requireTool(tool);
        TaskPhase value = phase == null ? TaskPhase.PROGRESS : phase;
        Map<String, String> facts = new LinkedHashMap<>();
        if (extraFacts != null) facts.putAll(extraFacts);
        facts.remove("percent");
        facts.remove("reason");
        facts.remove("detail");
        facts.put("phase", value.name().toLowerCase(Locale.ROOT));
        if (percent >= 0) facts.put("percent", Integer.toString(percent));
        if (reason != null && !reason.isBlank()) facts.put("reason", reason);
        if (detail != null && !detail.isBlank()) facts.put("detail", detail);
        return new TaskLogEvent(
                context,
                TaskLogEvent.Parent.EXECUTION,
                value == TaskPhase.PROGRESS ? LogEvents.TASK_PROGRESS : LogEvents.TASK_PHASE,
                tool,
                value.name().toLowerCase(Locale.ROOT),
                -1L,
                facts);
    }

    /** One decision the owning task already made: selected step, strategy, recipe or station. */
    public static TaskLogEvent decisionEvent(
            LogContext context, String event, String tool, String reason, Map<String, String> facts) {
        requireTool(tool);
        Map<String, String> bounded = new LinkedHashMap<>();
        if (facts != null) bounded.putAll(facts);
        if (reason != null && !reason.isBlank()) bounded.put("reason", reason);
        return new TaskLogEvent(context, TaskLogEvent.Parent.EXECUTION, event, tool, "decided", -1L, bounded);
    }

    /** One bounded snapshot of the work state, captured before a stall stop clears it. */
    public static TaskLogEvent snapshotEvent(
            LogContext context, String tool, String reason, Map<String, String> facts) {
        requireTool(tool);
        Map<String, String> bounded = new LinkedHashMap<>();
        if (facts != null) bounded.putAll(facts);
        bounded.put("reason", reason == null ? "" : reason);
        return new TaskLogEvent(
                context, TaskLogEvent.Parent.EXECUTION, LogEvents.TASK_SNAPSHOT, tool, "snapshot", -1L, bounded);
    }

    /** A nested span opening; its parent is the enclosing span or the execution. */
    public static TaskLogEvent spanStart(
            LogContext context, String operation, String reason, Map<String, String> facts) {
        Map<String, String> bounded = new LinkedHashMap<>();
        if (facts != null) bounded.putAll(facts);
        bounded.put("operation", operation == null ? "" : operation);
        if (reason != null && !reason.isBlank()) bounded.put("reason", reason);
        return new TaskLogEvent(
                context,
                context.parentSpan() > 0L ? TaskLogEvent.Parent.SPAN : TaskLogEvent.Parent.EXECUTION,
                LogEvents.SPAN_START,
                operation,
                "started",
                -1L,
                bounded);
    }

    /** A nested span closing; it descends from its own start, which the resolver addresses by execution and span. */
    public static TaskLogEvent spanEnd(
            LogContext context, String operation, String status, String reason, Map<String, String> facts) {
        Map<String, String> bounded = new LinkedHashMap<>();
        if (facts != null) bounded.putAll(facts);
        bounded.put("operation", operation == null ? "" : operation);
        if (reason != null && !reason.isBlank()) bounded.put("reason", reason);
        return new TaskLogEvent(
                context,
                TaskLogEvent.Parent.SPAN,
                LogEvents.SPAN_END,
                operation,
                status == null ? "unknown" : status,
                -1L,
                bounded);
    }

    /** One observed server tick gap; the reason is unknown, never inferred as a pause. */
    public static TaskLogEvent clockGap(long gapMs) {
        return clockGap(LogContext.NONE, gapMs, Map.of());
    }

    /** Captures a gap under the interrupted activation without inferring why time passed. */
    public static TaskLogEvent clockGap(LogContext context, long gapMs, Map<String, String> facts) {
        LogContext identity = context == null ? LogContext.NONE : context;
        Map<String, String> data = new LinkedHashMap<>();
        data.put("reason", "unknown");
        data.put("gapMs", Long.toString(Math.max(0L, gapMs)));
        if (facts != null) data.putAll(facts);
        data.put("reason", "unknown");
        data.put("gapMs", Long.toString(Math.max(0L, gapMs)));
        return new TaskLogEvent(
                identity,
                identity.execution() > 0 ? TaskLogEvent.Parent.EXECUTION : TaskLogEvent.Parent.INVOCATION,
                LogEvents.CLOCK_GAP,
                data.getOrDefault("tool", ""),
                "unknown",
                Math.max(0L, gapMs),
                data);
    }

    public static TaskLogEvent cancelEvent(LogContext context, String tool, String reason) {
        requireTool(tool);
        return new TaskLogEvent(
                context,
                TaskLogEvent.Parent.EXECUTION,
                LogEvents.TASK_CANCEL,
                tool,
                "cancelled",
                -1L,
                Map.of("reason", reason == null ? "" : reason));
    }

    public static TaskLogEvent holdEvent(LogContext context, String tool) {
        requireTool(tool);
        return new TaskLogEvent(
                context, TaskLogEvent.Parent.EXECUTION, LogEvents.TASK_HOLD, tool, "held", -1L, Map.of());
    }

    public static TaskLogEvent resumeEvent(LogContext context, String tool) {
        requireTool(tool);
        return new TaskLogEvent(
                context, TaskLogEvent.Parent.EXECUTION, LogEvents.TASK_RESUME, tool, "resumed", -1L, Map.of());
    }

    public static LogRecord start(
            UUID owner, UUID companion, String session, long task, long plan, long job, String tool) {
        requireTool(tool);
        return record(
                LogLevel.INFO,
                LogCategory.TASK,
                LogEvents.TASK_START,
                owner,
                companion,
                session,
                task,
                plan,
                job,
                tool,
                "task " + task + " started: " + tool,
                Map.of("tool", tool));
    }

    public static LogRecord progress(UUID owner, UUID companion, String session, long task, String text) {
        return record(
                LogLevel.DEBUG,
                LogCategory.TASK,
                LogEvents.TASK_PROGRESS,
                owner,
                companion,
                session,
                task,
                0L,
                0L,
                "",
                text == null ? "" : text,
                Map.of());
    }

    public static LogRecord finish(
            UUID owner,
            UUID companion,
            String session,
            long task,
            long plan,
            long job,
            String tool,
            boolean success,
            String summary) {
        requireTool(tool);
        return record(
                success ? LogLevel.INFO : LogLevel.ERROR,
                LogCategory.TASK,
                LogEvents.TASK_FINISH,
                owner,
                companion,
                session,
                task,
                plan,
                job,
                tool,
                tool + ": " + (success ? "SUCCESS" : "FAILURE") + " " + (summary == null ? "" : summary),
                Map.of("status", success ? "success" : "failure"));
    }

    public static LogRecord cancel(UUID owner, UUID companion, String session, long task, String tool, String reason) {
        requireTool(tool);
        return record(
                LogLevel.WARN,
                LogCategory.TASK,
                LogEvents.TASK_CANCEL,
                owner,
                companion,
                session,
                task,
                0L,
                0L,
                tool,
                tool + " cancelled: " + (reason == null ? "" : reason),
                Map.of());
    }

    public static LogRecord queueBlock(
            UUID owner,
            UUID companion,
            String session,
            long plan,
            long job,
            String tool,
            String reason,
            int remaining) {
        requireTool(tool);
        return record(
                LogLevel.WARN,
                LogCategory.QUEUE,
                LogEvents.QUEUE_BLOCK,
                owner,
                companion,
                session,
                0L,
                plan,
                job,
                tool,
                "blocked " + tool + ": " + (reason == null ? "" : reason),
                Map.of("remaining", Integer.toString(remaining)));
    }

    public static LogRecord restore(UUID owner, UUID companion, String session, String message) {
        return record(
                LogLevel.INFO,
                LogCategory.TASK,
                LogEvents.TASK_RESTORE,
                owner,
                companion,
                session,
                0L,
                0L,
                0L,
                "",
                message == null ? "" : message,
                Map.of());
    }

    public static LogRecord reject(UUID owner, UUID companion, String session, String message) {
        return record(
                LogLevel.WARN,
                LogCategory.TASK,
                LogEvents.TASK_REJECT,
                owner,
                companion,
                session,
                0L,
                0L,
                0L,
                "",
                message == null ? "" : message,
                Map.of());
    }

    private static void requireTool(String tool) {
        if (tool == null || tool.isBlank()) throw new IllegalArgumentException("tool name is required");
    }
}
