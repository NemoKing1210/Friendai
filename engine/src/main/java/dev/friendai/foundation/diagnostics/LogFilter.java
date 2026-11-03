package dev.friendai.foundation.diagnostics;

import java.util.Locale;
import java.util.Set;

/** Which records a reader or a sink keeps. Every blank or non-positive field means "any". */
public record LogFilter(
        LogLevel minLevel,
        Set<LogCategory> categories,
        String event,
        long turn,
        long task,
        String query,
        String trace,
        long execution,
        long request) {
    public LogFilter {
        categories = categories == null ? Set.of() : Set.copyOf(categories);
        event = event == null ? "" : event.strip();
        query = query == null ? "" : query.strip();
        trace = trace == null ? "" : trace.strip();
        execution = Math.max(0L, execution);
        request = Math.max(0L, request);
    }

    /** The historical shape: text, turn and task filters only. */
    public LogFilter(LogLevel minLevel, Set<LogCategory> categories, String event, long turn, long task, String query) {
        this(minLevel, categories, event, turn, task, query, "", 0L, 0L);
    }

    public static LogFilter all() {
        return new LogFilter(null, Set.of(), "", 0L, 0L, "");
    }

    public boolean accepts(LogRecord record) {
        if (record == null) return false;
        if (minLevel != null && !record.level().atLeast(minLevel)) return false;
        if (!categories.isEmpty() && !categories.contains(record.category())) return false;
        if (!event.isEmpty() && !event.equals(record.event())) return false;
        if (turn > 0 && record.turn() != turn) return false;
        if (task > 0 && record.task() != task) return false;
        if (!trace.isEmpty() && !trace.equals(record.data().getOrDefault("trace", ""))) return false;
        if (execution > 0 && number(record, "execution") != execution) return false;
        if (request > 0 && number(record, "request") != request) return false;
        if (!query.isEmpty()) {
            String needle = query.toLowerCase(Locale.ROOT);
            if (!record.message().toLowerCase(Locale.ROOT).contains(needle)) {
                boolean found = false;
                for (String value : record.data().values()) {
                    if (value.toLowerCase(Locale.ROOT).contains(needle)) {
                        found = true;
                        break;
                    }
                }
                if (!found) return false;
            }
        }
        return true;
    }

    private static long number(LogRecord record, String key) {
        String value = record.data().get(key);
        if (value == null || value.isBlank()) return 0L;
        try {
            return Long.parseLong(value.strip());
        } catch (NumberFormatException invalid) {
            return 0L;
        }
    }
}
