package dev.friendai.foundation.diagnostics;

import java.util.Set;

/**
 * The effective logging policy. A null level means "off": nothing reaches a file sink, while the in-memory ring
 * keeps working for the menu.
 */
public record LogSettings(
        LogLevel level,
        Set<LogCategory> categories,
        long maxFileBytes,
        long maxTotalBytes,
        int retentionDays,
        int maxSessionsPerOwner,
        boolean digest) {
    public LogSettings {
        categories = categories == null ? Set.of() : Set.copyOf(categories);
        maxFileBytes = Math.max(1L, maxFileBytes);
        maxTotalBytes = Math.max(maxFileBytes, maxTotalBytes);
        retentionDays = Math.max(1, retentionDays);
        maxSessionsPerOwner = Math.max(1, maxSessionsPerOwner);
    }

    public boolean allows(LogRecord record) {
        if (level == null || record == null) return false;
        if (!record.level().atLeast(level)) return false;
        return categories.isEmpty() || categories.contains(record.category());
    }

    public static LogSettings off() {
        return new LogSettings(null, Set.of(), 8_388_608L, 52_428_800L, 7, 20, true);
    }
}
