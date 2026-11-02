package dev.friendai.execution.log;

import dev.friendai.foundation.diagnostics.LogCategory;
import dev.friendai.foundation.diagnostics.LogEvents;
import dev.friendai.foundation.diagnostics.LogKind;
import dev.friendai.foundation.diagnostics.LogLevel;
import dev.friendai.foundation.diagnostics.LogRecord;
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
