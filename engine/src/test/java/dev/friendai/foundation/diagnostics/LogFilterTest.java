package dev.friendai.foundation.diagnostics;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LogFilterTest {
    private static LogRecord record(LogLevel level, LogCategory category, String event, long turn, long task) {
        return new LogRecord(
                1L,
                1L,
                level,
                category,
                null,
                event,
                null,
                null,
                "",
                turn,
                0L,
                0L,
                task,
                "",
                0L,
                -1L,
                "mining started at the hill",
                Map.of("item", "oak_log"),
                null);
    }

    @Test
    void levelThresholdKeepsThisLevelAndAbove() {
        LogFilter filter = new LogFilter(LogLevel.WARN, Set.of(), "", 0L, 0L, "");
        assertTrue(filter.accepts(record(LogLevel.WARN, LogCategory.TASK, LogEvents.TASK_START, 0L, 0L)));
        assertTrue(filter.accepts(record(LogLevel.ERROR, LogCategory.TASK, LogEvents.TASK_START, 0L, 0L)));
        assertFalse(filter.accepts(record(LogLevel.INFO, LogCategory.TASK, LogEvents.TASK_START, 0L, 0L)));
    }

    @Test
    void categoriesNarrowTheResult() {
        LogFilter filter = new LogFilter(null, Set.of(LogCategory.TOOL), "", 0L, 0L, "");
        assertTrue(filter.accepts(record(LogLevel.INFO, LogCategory.TOOL, LogEvents.TOOL_CALL, 0L, 0L)));
        assertFalse(filter.accepts(record(LogLevel.INFO, LogCategory.TASK, LogEvents.TOOL_CALL, 0L, 0L)));
    }

    @Test
    void eventMatchesExactly() {
        LogFilter filter = new LogFilter(null, Set.of(), LogEvents.TOOL_CALL, 0L, 0L, "");
        assertTrue(filter.accepts(record(LogLevel.INFO, LogCategory.TOOL, LogEvents.TOOL_CALL, 0L, 0L)));
        assertFalse(filter.accepts(record(LogLevel.INFO, LogCategory.TOOL, LogEvents.TOOL_RESULT, 0L, 0L)));
    }

    @Test
    void turnAndTaskNarrowTheResult() {
        assertTrue(new LogFilter(null, Set.of(), "", 4L, 0L, "")
                .accepts(record(LogLevel.INFO, LogCategory.BRAIN, LogEvents.TURN_START, 4L, 0L)));
        assertFalse(new LogFilter(null, Set.of(), "", 4L, 0L, "")
                .accepts(record(LogLevel.INFO, LogCategory.BRAIN, LogEvents.TURN_START, 5L, 0L)));
        assertTrue(new LogFilter(null, Set.of(), "", 0L, 9L, "")
                .accepts(record(LogLevel.INFO, LogCategory.TASK, LogEvents.TASK_START, 0L, 9L)));
        assertFalse(new LogFilter(null, Set.of(), "", 0L, 9L, "")
                .accepts(record(LogLevel.INFO, LogCategory.TASK, LogEvents.TASK_START, 0L, 8L)));
    }

    @Test
    void queryMatchesTheMessageOrADataValue() {
        LogFilter byMessage = new LogFilter(null, Set.of(), "", 0L, 0L, "HILL");
        assertTrue(byMessage.accepts(record(LogLevel.INFO, LogCategory.TASK, LogEvents.TASK_START, 0L, 0L)));
        LogFilter byData = new LogFilter(null, Set.of(), "", 0L, 0L, "oak");
        assertTrue(byData.accepts(record(LogLevel.INFO, LogCategory.TASK, LogEvents.TASK_START, 0L, 0L)));
        LogFilter miss = new LogFilter(null, Set.of(), "", 0L, 0L, "diamond");
        assertFalse(miss.accepts(record(LogLevel.INFO, LogCategory.TASK, LogEvents.TASK_START, 0L, 0L)));
    }

    @Test
    void blankFilterAcceptsEveryRecord() {
        LogFilter filter = LogFilter.all();
        assertTrue(filter.accepts(record(LogLevel.TRACE, LogCategory.CLIENT, LogEvents.CLIENT_ERROR, 0L, 0L)));
        assertTrue(filter.accepts(record(LogLevel.ERROR, LogCategory.SERVER, LogEvents.SERVER_ERROR, 3L, 4L)));
    }

    @Test
    void aNullKindDoesNotAffectFiltering() {
        LogRecord record = record(LogLevel.INFO, LogCategory.SERVER, LogEvents.SERVER_WARN, 0L, 0L);
        assertTrue(LogFilter.all().accepts(record));
        assertFalse(new LogFilter(LogLevel.WARN, Set.of(), "", 0L, 0L, "").accepts(record));
    }
}
