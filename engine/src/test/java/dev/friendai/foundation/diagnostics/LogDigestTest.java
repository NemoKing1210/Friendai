package dev.friendai.foundation.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LogDigestTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static LogFileHeader header() {
        return new LogFileHeader(
                1, "7f3c", "0.160.0", "26.3", OWNER, "Alex", "normal", List.of("brain", "tool"), 1_700_000_000_000L);
    }

    private static LogRecord record(
            long seq, LogLevel level, LogCategory category, LogKind kind, String event, long cause, String message) {
        return new LogRecord(
                seq,
                1_700_000_000_000L + seq,
                level,
                category,
                kind,
                event,
                OWNER,
                null,
                "abc",
                4L,
                0L,
                0L,
                0L,
                "",
                cause,
                -1L,
                message,
                Map.of(),
                null);
    }

    @Test
    void rendersAChainInOrderAndIndented() {
        LogRecord turn =
                record(1, LogLevel.INFO, LogCategory.BRAIN, LogKind.USER, LogEvents.TURN_START, 0, "mine 3 logs");
        LogRecord call =
                record(2, LogLevel.INFO, LogCategory.TOOL, LogKind.TOOL_CALL, LogEvents.TOOL_CALL, 1, "mine_block_at");
        LogRecord result =
                record(3, LogLevel.INFO, LogCategory.TOOL, LogKind.TOOL_RESULT, LogEvents.TOOL_RESULT, 2, "started");
        LogRecord error =
                record(4, LogLevel.ERROR, LogCategory.TASK, LogKind.ERROR, LogEvents.TASK_REJECT, 3, "no pickaxe");
        String text = LogDigest.render(header(), List.of(turn, call, result, error), 100_000);
        int turnAt = text.indexOf("mine 3 logs");
        int callAt = text.indexOf("mine_block_at");
        int resultAt = text.indexOf("started");
        int errorAt = text.indexOf("no pickaxe");
        assertTrue(turnAt > 0 && turnAt < callAt && callAt < resultAt && resultAt < errorAt);
        assertTrue(text.contains("\n  "));
        assertTrue(text.contains("## Errors and chains"));
    }

    @Test
    void anUnknownCausePrintsRoot() {
        LogRecord orphan =
                record(5, LogLevel.ERROR, LogCategory.TASK, LogKind.ERROR, LogEvents.TASK_REJECT, 99, "gone");
        String text = LogDigest.render(header(), List.of(orphan), 100_000);
        assertTrue(text.contains("(root)"));
        assertTrue(text.contains("gone"));
    }

    @Test
    void countersReportDroppedAndSkipped() {
        String text = LogDigest.render(
                header(),
                List.of(record(1, LogLevel.WARN, LogCategory.SERVER, null, LogEvents.SERVER_WARN, 0, "slow")),
                7,
                true,
                100_000);
        assertTrue(text.contains("dropped: 7"));
        assertTrue(text.contains("skipped lines: 1"));
        assertTrue(text.contains("warnings: 1"));
    }

    @Test
    void anEmptySessionStillRendersEveryHeading() {
        String text = LogDigest.render(header(), List.of(), 100_000);
        for (String heading :
                List.of("## What was asked", "## What was done", "## Errors and chains", "## Outcome", "## Counters")) {
            assertTrue(text.contains(heading), heading);
        }
        assertTrue(text.contains("# Session 2023-11-14"));
    }

    @Test
    void aRecordWithoutAKindStillRenders() {
        String text = LogDigest.render(
                header(),
                List.of(record(1, LogLevel.INFO, LogCategory.SERVER, null, LogEvents.SERVER_START, 0, "booted")),
                100_000);
        assertTrue(text.contains("booted"));
        assertTrue(text.contains("## What was done"));
    }

    @Test
    void outputIsBoundedAndEndsWithTheMarker() {
        LogRecord longRecord = record(
                1, LogLevel.INFO, LogCategory.BRAIN, LogKind.ASSISTANT, LogEvents.ASSISTANT_TEXT, 0, "x".repeat(500));
        String text = LogDigest.render(header(), List.of(longRecord), 200);
        assertTrue(text.length() <= 200);
        assertTrue(text.endsWith("[truncated]"));
    }

    @Test
    void theHeaderNamesTheSession() {
        String text = LogDigest.render(header(), List.of(), 100_000);
        assertTrue(text.contains("7f3c"));
        assertTrue(text.contains("0.160.0"));
        assertTrue(text.contains("26.3"));
        assertTrue(text.contains("level: normal"));
        assertTrue(text.contains("Alex"));
        assertEquals(true, text.startsWith("# Session"));
    }
}
