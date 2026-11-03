package dev.friendai.agent.autonomy;

import dev.friendai.foundation.diagnostics.LogCategory;
import dev.friendai.foundation.diagnostics.LogEvents;
import dev.friendai.foundation.diagnostics.LogKind;
import dev.friendai.foundation.diagnostics.LogLevel;
import dev.friendai.foundation.diagnostics.LogRecord;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Builds the durable free-time records: one when a shift's plan is created, one when it ends with the diagnosis that
 * explains it, and one for every owner-facing line. Pure; the pipeline assigns the sequence and timestamp.
 */
public final class FreeTimeLogEvents {
    private FreeTimeLogEvents() {}

    /** A shift whose plan was created. */
    public static LogRecord start(UUID owner, UUID companion, String session, long plan, String need, String goal) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("plan", Long.toString(plan));
        data.put("need", text(need));
        data.put("goal", text(goal));
        return record(
                LogEvents.SHIFT_START,
                LogLevel.INFO,
                owner,
                companion,
                session,
                plan,
                "shift started: " + text(goal),
                data);
    }

    /** A shift that ended, with the cause the recovery advisor found (empty on success). */
    public static LogRecord end(
            UUID owner,
            UUID companion,
            String session,
            long plan,
            boolean success,
            String stopWhen,
            String summary,
            String diagnosisKind,
            String diagnosisDetail) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("plan", Long.toString(plan));
        data.put("success", Boolean.toString(success));
        data.put("stopWhen", text(stopWhen));
        data.put("diagnosisKind", text(diagnosisKind));
        data.put("diagnosisDetail", text(diagnosisDetail));
        data.put("summary", text(summary));
        return record(
                LogEvents.SHIFT_END,
                success ? LogLevel.INFO : LogLevel.WARN,
                owner,
                companion,
                session,
                plan,
                "shift ended: " + (success ? "succeeded" : "failed")
                        + (text(summary).isEmpty() ? "" : ": " + text(summary)),
                data);
    }

    /** One owner-facing line, recorded by its translation key so the text can be looked up later. */
    public static LogRecord report(UUID owner, UUID companion, String session, long plan, String key) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("plan", Long.toString(plan));
        data.put("key", text(key));
        return record(
                LogEvents.SHIFT_REPORT,
                LogLevel.INFO,
                owner,
                companion,
                session,
                plan,
                "direct line: " + text(key),
                data);
    }

    private static LogRecord record(
            String event,
            LogLevel level,
            UUID owner,
            UUID companion,
            String session,
            long plan,
            String message,
            Map<String, String> data) {
        return new LogRecord(
                0L,
                0L,
                level,
                LogCategory.TASK,
                LogKind.TASK,
                event,
                owner,
                companion,
                session == null ? "" : session,
                0L,
                plan,
                0L,
                0L,
                "",
                0L,
                -1L,
                message,
                data,
                null);
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
