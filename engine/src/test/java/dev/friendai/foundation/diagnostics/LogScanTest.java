package dev.friendai.foundation.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LogScanTest {
    private static LogRecord record(long seq, LogLevel level, long turn, String message) {
        return new LogRecord(
                seq,
                1_700_000_000_000L + seq,
                level,
                LogCategory.TASK,
                null,
                LogEvents.TASK_START,
                null,
                null,
                "",
                turn,
                0L,
                0L,
                0L,
                "",
                0L,
                -1L,
                message,
                Map.of(),
                null);
    }

    private static Path write(Path dir, List<LogRecord> records) throws IOException {
        StringBuilder text = new StringBuilder();
        text.append(LogJson.encodeHeader(new LogFileHeader(
                        1,
                        "7f3c",
                        "New World",
                        "0.160.0",
                        "26.3",
                        null,
                        "",
                        "normal",
                        List.of("task"),
                        1_700_000_000_000L)))
                .append('\n');
        for (LogRecord record : records) {
            text.append(LogJson.encode(record)).append('\n');
        }
        Path file = dir.resolve("boot-7f3c.jsonl");
        Files.writeString(file, text.toString(), StandardCharsets.UTF_8);
        return file;
    }

    @Test
    void filtersByLevelAndSkipsTheHeader(@TempDir Path dir) throws IOException {
        List<LogRecord> records = new java.util.ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            records.add(record(i, i % 2 == 0 ? LogLevel.WARN : LogLevel.INFO, 1, "m" + i));
        }
        Path file = write(dir, records);
        LogScanResult result = LogScan.scan(
                file, ScanLimits.DEFAULT, new LogFilter(LogLevel.WARN, Set.of(), "", 0L, 0L, ""), 200, false);
        assertEquals(50, result.records().size());
        assertTrue(result.records().stream().allMatch(r -> r.level() == LogLevel.WARN));
        assertFalse(result.skippedLine());
    }

    @Test
    void filtersByTurn(@TempDir Path dir) throws IOException {
        Path file = write(
                dir,
                List.of(
                        record(1, LogLevel.INFO, 7, "seven"),
                        record(2, LogLevel.INFO, 8, "eight"),
                        record(3, LogLevel.INFO, 7, "seven again")));
        LogScanResult result =
                LogScan.scan(file, ScanLimits.DEFAULT, new LogFilter(null, Set.of(), "", 7L, 0L, ""), 10, false);
        assertEquals(
                List.of("seven", "seven again"),
                result.records().stream().map(LogRecord::message).toList());
    }

    @Test
    void honoursLimitAndOrder(@TempDir Path dir) throws IOException {
        List<LogRecord> records = new java.util.ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            records.add(record(i, LogLevel.INFO, 1, "m" + i));
        }
        Path file = write(dir, records);
        LogScanResult newest = LogScan.scan(file, ScanLimits.DEFAULT, LogFilter.all(), 3, true);
        assertEquals(
                List.of(10L, 9L, 8L),
                newest.records().stream().map(LogRecord::seq).toList());
        assertTrue(newest.truncated());
        LogScanResult oldest = LogScan.scan(file, ScanLimits.DEFAULT, LogFilter.all(), 3, false);
        assertEquals(
                List.of(1L, 2L, 3L),
                oldest.records().stream().map(LogRecord::seq).toList());
        assertEquals(1L, oldest.oldestSeq());
        assertEquals(3L, oldest.newestSeq());
    }

    @Test
    void skipsATornLastLine(@TempDir Path dir) throws IOException {
        List<LogRecord> records = new java.util.ArrayList<>();
        for (int i = 1; i <= 98; i++) {
            records.add(record(i, LogLevel.INFO, 1, "m" + i));
        }
        Path file = write(dir, records);
        Files.writeString(file, "{\"seq\":99", StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
        LogScanResult result = LogScan.scan(file, ScanLimits.DEFAULT, LogFilter.all(), 200, false);
        assertEquals(98, result.records().size());
        assertTrue(result.skippedLine());
    }

    @Test
    void readsOnlyTheTailWithinTheByteCap(@TempDir Path dir) throws IOException {
        List<LogRecord> records = new java.util.ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            records.add(record(i, LogLevel.INFO, 1, "m" + i));
        }
        Path file = write(dir, records);
        LogScanResult result = LogScan.scan(file, new ScanLimits(2000, 5000), LogFilter.all(), 200, false);
        assertTrue(result.truncated());
        assertTrue(result.records().size() < 100);
        assertEquals(100L, result.records().get(result.records().size() - 1).seq());
    }

    @Test
    void capsTheDecodedRecordCount(@TempDir Path dir) throws IOException {
        List<LogRecord> records = new java.util.ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            records.add(record(i, LogLevel.INFO, 1, "m" + i));
        }
        Path file = write(dir, records);
        LogScanResult result = LogScan.scan(file, new ScanLimits(2 << 20, 20), LogFilter.all(), 200, false);
        assertEquals(20, result.records().size());
        assertTrue(result.truncated());
    }

    @Test
    void aMissingFileIsEmpty(@TempDir Path dir) {
        LogScanResult result = LogScan.scan(dir.resolve("nope.jsonl"), ScanLimits.DEFAULT, LogFilter.all(), 10, false);
        assertEquals(List.of(), result.records());
        assertFalse(result.truncated());
        assertFalse(result.skippedLine());
        assertEquals(0L, result.oldestSeq());
    }
}
