package dev.friendai.foundation.diagnostics;

import java.util.List;

/** What one bounded scan of a log file produced. */
public record LogScanResult(
        List<LogRecord> records, boolean truncated, boolean skippedLine, long oldestSeq, long newestSeq) {
    public LogScanResult {
        records = records == null ? List.of() : List.copyOf(records);
    }

    public static LogScanResult empty() {
        return new LogScanResult(List.of(), false, false, 0L, 0L);
    }
}
