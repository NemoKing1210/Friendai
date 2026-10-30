package dev.friendai.foundation.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

class DebugLogTest {
    private static DebugLog log(int capacity, int maxChars, UnaryOperator<String> redactor) {
        return new DebugLog(capacity, maxChars, redactor, () -> 1000L);
    }

    @Test
    void evictsOldestBeyondCapacity() {
        DebugLog log = log(3, 100, UnaryOperator.identity());
        for (int i = 1; i <= 5; i++) {
            log.add(LogKind.USER, "m" + i);
        }
        List<LogEntry> all = log.after(0, 10);
        assertEquals(List.of(3L, 4L, 5L), all.stream().map(LogEntry::id).toList());
    }

    @Test
    void idsAreMonotonic() {
        DebugLog log = log(10, 100, UnaryOperator.identity());
        assertEquals(1L, log.add(LogKind.USER, "a").id());
        assertEquals(2L, log.add(LogKind.ASSISTANT, "b").id());
        assertEquals(2L, log.lastId());
    }

    @Test
    void afterReturnsOnlyNewerAndLimitsToLast() {
        DebugLog log = log(10, 100, UnaryOperator.identity());
        for (int i = 1; i <= 5; i++) {
            log.add(LogKind.USER, "m" + i);
        }
        assertEquals(List.of(4L, 5L), log.after(0, 2).stream().map(LogEntry::id).toList());
        assertEquals(List.of(5L), log.after(4, 10).stream().map(LogEntry::id).toList());
        assertTrue(log.after(5, 10).isEmpty());
    }

    @Test
    void truncatesLongText() {
        DebugLog log = log(10, 10, UnaryOperator.identity());
        String stored = log.add(LogKind.TOOL_RESULT, "x".repeat(50)).text();
        assertEquals(11, stored.length());
        assertTrue(stored.endsWith("…"));
    }

    @Test
    void redactsBeforeTruncating() {
        DebugLog log = log(10, 100, s -> s.replace("SECRET", "***"));
        String stored = log.add(LogKind.ERROR, "key=SECRET end").text();
        assertFalse(stored.contains("SECRET"));
        assertEquals("key=*** end", stored);
    }

    @Test
    void recordsKindAndTime() {
        DebugLog log = log(10, 100, UnaryOperator.identity());
        LogEntry e = log.add(LogKind.TASK, "t");
        assertEquals(LogKind.TASK, e.kind());
        assertEquals(1000L, e.timeMillis());
    }

    @Test
    void queryFiltersKindAndIdWindow() {
        DebugLog log = log(10, 100, UnaryOperator.identity());
        log.add(LogKind.USER, "u");
        log.add(LogKind.ASSISTANT, "a");
        log.add(LogKind.USER, "u2");
        log.add(LogKind.TOOL_CALL, "t");

        assertEquals(
                List.of(2L),
                log.query(new DebugLog.Query(java.util.Set.of(LogKind.ASSISTANT), 0, 0, 10, false)).stream()
                        .map(LogEntry::id)
                        .toList());
        assertEquals(
                List.of(3L),
                log.query(new DebugLog.Query(java.util.Set.of(), 2, 4, 10, false)).stream()
                        .map(LogEntry::id)
                        .toList());
    }

    @Test
    void queryReturnsTheRequestedEndAndReversesNewestFirst() {
        DebugLog log = log(10, 100, UnaryOperator.identity());
        for (int i = 1; i <= 5; i++) {
            log.add(LogKind.USER, "m" + i);
        }
        assertEquals(
                List.of(1L, 2L),
                log.query(new DebugLog.Query(java.util.Set.of(), 0, 0, 2, false)).stream()
                        .map(LogEntry::id)
                        .toList());
        assertEquals(
                List.of(5L, 4L),
                log.query(new DebugLog.Query(java.util.Set.of(), 0, 0, 2, true)).stream()
                        .map(LogEntry::id)
                        .toList());
    }

    @Test
    void firstIdTracksTheBuffer() {
        DebugLog log = log(2, 100, UnaryOperator.identity());
        assertEquals(1L, log.firstId());
        log.add(LogKind.USER, "a");
        log.add(LogKind.USER, "b");
        log.add(LogKind.USER, "c");
        assertEquals(2L, log.firstId());
        assertEquals(2L, log.size());
    }
}
