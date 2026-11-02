package dev.friendai.agent.turn;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.foundation.diagnostics.LogCategory;
import dev.friendai.foundation.diagnostics.LogEvents;
import dev.friendai.foundation.diagnostics.LogFilter;
import dev.friendai.foundation.diagnostics.LogJson;
import dev.friendai.foundation.diagnostics.LogLevel;
import dev.friendai.foundation.diagnostics.LogNames;
import dev.friendai.foundation.diagnostics.LogRecord;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LogSearchTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Caller OWNER_CALLER = new Caller(OWNER, "Alex", AccessLevel.OWNER);
    private static final long NOW = Instant.parse("2026-11-03T12:00:00Z").toEpochMilli();

    private static LogRecord record(long seq, LogLevel level, long turn, String message) {
        return new LogRecord(
                seq,
                NOW,
                level,
                LogCategory.TASK,
                null,
                LogEvents.TASK_START,
                OWNER,
                null,
                "s",
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

    private static Path day(Path ownerRoot, long at, List<LogRecord> records) throws IOException {
        Path dir = ownerRoot.resolve(LogNames.dayDir(at));
        Files.createDirectories(dir);
        Path file = dir.resolve("session-120000-7f3c.jsonl");
        StringBuilder text = new StringBuilder();
        for (LogRecord record : records) {
            text.append(LogJson.encode(record)).append('\n');
        }
        Files.writeString(file, text.toString(), StandardCharsets.UTF_8);
        return file;
    }

    private static String search(Path root, LogFilter filter, long before, int limit, boolean newest, int maxChars) {
        return LogSearch.request(
                OWNER_CALLER, OWNER, root, filter, before, limit, newest, maxChars, UnaryOperator.identity(), NOW);
    }

    @Test
    void readsTodayAndYesterdayOnly(@TempDir Path dir) throws IOException {
        Path root = dir.resolve("owners").resolve(OWNER.toString());
        day(root, NOW, List.of(record(10, LogLevel.INFO, 1, "today")));
        day(root, NOW - 86_400_000L, List.of(record(5, LogLevel.INFO, 2, "yesterday")));
        day(root, NOW - 3 * 86_400_000L, List.of(record(1, LogLevel.INFO, 3, "older")));
        String text = search(root, LogFilter.all(), 0L, 50, false, 100_000);
        assertTrue(text.contains("today"));
        assertTrue(text.contains("yesterday"));
        assertFalse(text.contains("older"));
    }

    @Test
    void filtersByLevelEventAndTurn(@TempDir Path dir) throws IOException {
        Path root = dir.resolve("owners").resolve(OWNER.toString());
        day(
                root,
                NOW,
                List.of(
                        record(1, LogLevel.INFO, 7, "info seven"),
                        record(2, LogLevel.WARN, 8, "warn eight"),
                        record(3, LogLevel.INFO, 7, "info seven again")));
        assertTrue(search(root, new LogFilter(LogLevel.WARN, Set.of(), "", 0L, 0L, ""), 0L, 50, false, 100_000)
                .contains("warn eight"));
        String byTurn = search(root, new LogFilter(null, Set.of(), "", 7L, 0L, ""), 0L, 50, false, 100_000);
        assertTrue(byTurn.contains("info seven"));
        assertFalse(byTurn.contains("warn eight"));
        String byEvent =
                search(root, new LogFilter(null, Set.of(), LogEvents.TASK_START, 0L, 0L, ""), 0L, 50, false, 100_000);
        assertTrue(byEvent.contains("info seven"));
        String missing =
                search(root, new LogFilter(null, Set.of(), LogEvents.TOOL_CALL, 0L, 0L, ""), 0L, 50, false, 100_000);
        assertTrue(missing.contains("No records match"));
    }

    @Test
    void beforeContinuesAndLimitTruncates(@TempDir Path dir) throws IOException {
        Path root = dir.resolve("owners").resolve(OWNER.toString());
        day(
                root,
                NOW,
                List.of(
                        record(1, LogLevel.INFO, 1, "one"),
                        record(2, LogLevel.INFO, 1, "two"),
                        record(3, LogLevel.INFO, 1, "three")));
        String page = search(root, LogFilter.all(), 0L, 2, true, 100_000);
        assertTrue(page.contains("three"));
        assertTrue(page.contains("two"));
        assertFalse(page.contains("one"));
        assertTrue(page.contains("before=2"));
        String older = search(root, LogFilter.all(), 2L, 10, false, 100_000);
        assertTrue(older.contains("one"));
        assertFalse(older.contains("two"));
    }

    @Test
    void redactsSecretsInTheReturnedLines(@TempDir Path dir) throws IOException {
        Path root = dir.resolve("owners").resolve(OWNER.toString());
        day(root, NOW, List.of(record(1, LogLevel.INFO, 1, "key SECRET here")));
        String text = LogSearch.request(
                OWNER_CALLER,
                OWNER,
                root,
                LogFilter.all(),
                0L,
                10,
                false,
                100_000,
                value -> value.replace("SECRET", "***"),
                NOW);
        assertTrue(text.contains("***"));
        assertFalse(text.contains("SECRET"));
    }

    @Test
    void missingDirectoriesAnswerClearly(@TempDir Path dir) {
        assertTrue(search(dir.resolve("absent"), LogFilter.all(), 0L, 10, false, 100_000)
                .contains("No files yet"));
        assertTrue(search(dir, LogFilter.all(), 0L, 10, false, 100_000).contains("File logging is off"));
    }

    @Test
    void aNonOwnerIsRefused(@TempDir Path dir) {
        assertThrows(
                IllegalArgumentException.class,
                () -> LogSearch.request(
                        new Caller(OWNER, "Alex", AccessLevel.GUEST),
                        OWNER,
                        dir,
                        LogFilter.all(),
                        0L,
                        10,
                        false,
                        100_000,
                        UnaryOperator.identity(),
                        NOW));
    }

    @Test
    void oldestOrderTruncationDoesNotOfferAnUnusableContinuation(@TempDir Path dir) throws IOException {
        Path root = dir.resolve("owners").resolve(OWNER.toString());
        day(
                root,
                NOW,
                List.of(
                        record(1, LogLevel.INFO, 1, "one"),
                        record(2, LogLevel.INFO, 1, "two"),
                        record(3, LogLevel.INFO, 1, "three")));
        String text = search(root, LogFilter.all(), 0L, 2, false, 100_000);
        assertTrue(text.contains("truncated: true"));
        assertFalse(text.contains("continuation:"));
    }

    @Test
    void maxCharsCutsTheBodyAndKeepsTheContinuation(@TempDir Path dir) throws IOException {
        Path root = dir.resolve("owners").resolve(OWNER.toString());
        day(root, NOW, List.of(record(1, LogLevel.INFO, 1, "x".repeat(300))));
        String text = search(root, LogFilter.all(), 0L, 10, true, 120);
        assertTrue(text.length() <= 120);
        assertTrue(text.contains("continuation: logs_search before=1"));
    }
}
