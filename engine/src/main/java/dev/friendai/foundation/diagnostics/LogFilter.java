package dev.friendai.foundation.diagnostics;

import java.util.Locale;
import java.util.Set;

/** Which records a reader or a sink keeps. Every blank or non-positive field means "any". */
public record LogFilter(
        LogLevel minLevel, Set<LogCategory> categories, String event, long turn, long task, String query) {
    public LogFilter {
        categories = categories == null ? Set.of() : Set.copyOf(categories);
        event = event == null ? "" : event.strip();
        query = query == null ? "" : query.strip();
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
}
