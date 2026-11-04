package dev.friendai.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.foundation.diagnostics.Completeness;
import dev.friendai.foundation.diagnostics.IncidentTimeline;
import dev.friendai.foundation.diagnostics.LogCategory;
import dev.friendai.foundation.diagnostics.LogEvents;
import dev.friendai.foundation.diagnostics.LogFileHeader;
import dev.friendai.foundation.diagnostics.LogFilter;
import dev.friendai.foundation.diagnostics.LogJson;
import dev.friendai.foundation.diagnostics.LogLevel;
import dev.friendai.foundation.diagnostics.LogRecord;
import dev.friendai.foundation.diagnostics.LogScan;
import dev.friendai.foundation.diagnostics.LogScanResult;
import dev.friendai.foundation.diagnostics.ScanLimits;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Synthetic durable fixtures for the diagnostic workflow: what a maintainer actually reads after an incident. The
 * assertions pair a source fact (what the file contains) with the rendered evidence, and never depend on private
 * player logs.
 */
class DiagnosticIncidentFixturesTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-0000000000cc");

    private static LogRecord record(
            long seq,
            String event,
            String session,
            long cause,
            Map<String, String> data,
            String message,
            long timeMillis) {
        return new LogRecord(
                seq,
                timeMillis,
                LogLevel.INFO,
                LogCategory.TASK,
                null,
                event,
                OWNER,
                null,
                session,
                3L,
                0L,
                0L,
                0L,
                "obtain_item",
                cause,
                -1L,
                message,
                data,
                null);
    }

    private static LogFileHeader header() {
        return new LogFileHeader(
                1, "boot-9", "New World", "0.200.0", "26.3", OWNER, "Alex", "normal", List.of("task"), 1L);
    }

    private static Path write(Path dir, String name, LogFileHeader header, List<String> lines) throws IOException {
        Files.createDirectories(dir);
        StringBuilder text = new StringBuilder(LogJson.encodeHeader(header)).append('\n');
        for (String line : lines) text.append(line).append('\n');
        Path file = dir.resolve(name);
        Files.writeString(file, text.toString(), StandardCharsets.UTF_8);
        return file;
    }

    @Test
    void aTornAndOversizedFileStillYieldsAUsefulOpenExecution(@TempDir Path dir) throws IOException {
        LogRecord start = record(
                1L,
                LogEvents.TASK_START,
                "s1",
                0L,
                Map.of("trace", "t-1", "execution", "7"),
                "obtain: started",
                1_000L);
        LogRecord progress = record(
                2L,
                LogEvents.TASK_PROGRESS,
                "s1",
                1L,
                Map.of("trace", "t-1", "execution", "7", "phase", "progress"),
                "mine: running",
                1_100L);
        Path file = write(
                dir,
                "session-1.jsonl",
                header(),
                List.of(
                        LogJson.encode(start),
                        LogJson.encode(progress),
                        "{\"schema\":1,\"truncated",
                        "x".repeat(9_000)));

        LogScanResult scan = LogScan.scan(file, ScanLimits.DEFAULT, LogFilter.all(), 1_000, false);
        IncidentTimeline timeline = IncidentTimeline.build(
                header(),
                scan.records(),
                new Completeness(scan.records().size(), 0, scan.skippedLine() ? 1 : 0, 0, 0L, 0, false));

        assertEquals(2, scan.records().size(), "the torn and oversized lines are skipped, not fatal");
        assertTrue(scan.skippedLine(), "the scan reports that it skipped something");
        assertEquals(1, timeline.completeness().openExecutions());
        assertTrue(timeline.executions().get(0).open());
        String report = timeline.render(8_000);
        assertTrue(report.contains("PARTIAL"), "a skipped line makes the report partial");
        assertTrue(report.contains("NO TERMINAL"));
        assertTrue(report.contains("exec 7"));
    }

    @Test
    void twoRotatedPartsOfDifferentStreamsKeepTheirOwnChronologies(@TempDir Path dir) throws IOException {
        Path s1 = dir.resolve("s1");
        Path s2 = dir.resolve("s2");
        write(
                s1,
                "session-1.jsonl",
                header(),
                List.of(LogJson.encode(
                        record(14L, LogEvents.TASK_START, "s1", 0L, Map.of("execution", "1"), "one", 1_000L))));
        write(
                s2,
                "session-2.jsonl",
                header(),
                List.of(LogJson.encode(
                        record(14L, LogEvents.TASK_START, "s2", 0L, Map.of("execution", "1"), "two", 2_000L))));

        LogScanResult first =
                LogScan.scan(s1.resolve("session-1.jsonl"), ScanLimits.DEFAULT, LogFilter.all(), 100, false);
        LogScanResult second =
                LogScan.scan(s2.resolve("session-2.jsonl"), ScanLimits.DEFAULT, LogFilter.all(), 100, false);
        List<LogRecord> mixed = new java.util.ArrayList<>();
        mixed.addAll(first.records());
        mixed.addAll(second.records());
        IncidentTimeline timeline = IncidentTimeline.build(header(), mixed, Completeness.NONE);

        assertEquals(2, timeline.executions().size(), "a duplicate legacy seq never joins two streams");
        assertEquals(2, timeline.completeness().openExecutions());
    }

    @Test
    void aClockRollbackIsReportedAsZeroNotAsNegativeTime(@TempDir Path dir) throws IOException {
        LogRecord start = record(1L, LogEvents.TASK_START, "s1", 0L, Map.of("execution", "3"), "start", 5_000L);
        LogRecord finish = record(
                2L, LogEvents.TASK_FINISH, "s1", 1L, Map.of("execution", "3", "activeTicks", "4"), "finish", 1_000L);
        Path file = write(dir, "session-1.jsonl", header(), List.of(LogJson.encode(start), LogJson.encode(finish)));

        IncidentTimeline timeline = IncidentTimeline.build(
                header(),
                LogScan.scan(file, ScanLimits.DEFAULT, LogFilter.all(), 100, false)
                        .records(),
                Completeness.NONE);

        assertEquals(0L, timeline.executions().get(0).durationMs());
        assertEquals(4L, timeline.executions().get(0).activeTicks());
    }

    @Test
    void aCrashBeforeTheTerminalIsNeverReportedAsSuccess(@TempDir Path dir) throws IOException {
        LogRecord start = record(1L, LogEvents.TASK_START, "s1", 0L, Map.of("execution", "5"), "start", 1_000L);
        Path file = write(dir, "session-1.jsonl", header(), List.of(LogJson.encode(start)));

        IncidentTimeline timeline = IncidentTimeline.build(
                header(),
                LogScan.scan(file, ScanLimits.DEFAULT, LogFilter.all(), 100, false)
                        .records(),
                Completeness.NONE);
        String report = timeline.render(8_000);

        assertTrue(report.contains("NO TERMINAL"));
        assertFalse(report.contains("(success"));
        assertEquals(1, timeline.completeness().openExecutions());
    }
}
