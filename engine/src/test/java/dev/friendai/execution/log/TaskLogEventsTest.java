package dev.friendai.execution.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.friendai.foundation.diagnostics.LogCategory;
import dev.friendai.foundation.diagnostics.LogEvents;
import dev.friendai.foundation.diagnostics.LogKind;
import dev.friendai.foundation.diagnostics.LogLevel;
import dev.friendai.foundation.diagnostics.LogRecord;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TaskLogEventsTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID COMPANION = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void startCarriesEveryIdentifier() {
        LogRecord record = TaskLogEvents.start(OWNER, COMPANION, "s", 7L, 3L, 9L, "mine_block_at");
        assertEquals(LogEvents.TASK_START, record.event());
        assertEquals(LogCategory.TASK, record.category());
        assertEquals(LogKind.TASK, record.kind());
        assertEquals(LogLevel.INFO, record.level());
        assertEquals(7L, record.task());
        assertEquals(3L, record.plan());
        assertEquals(9L, record.job());
        assertEquals("mine_block_at", record.toolCall());
        assertEquals(OWNER, record.owner());
        assertEquals(COMPANION, record.companion());
        assertEquals(-1L, record.durationMs());
    }

    @Test
    void finishSeparatesSuccessFromFailure() {
        LogRecord success = TaskLogEvents.finish(OWNER, COMPANION, "s", 7L, 0L, 0L, "mine_block_at", true, "done");
        LogRecord failure = TaskLogEvents.finish(OWNER, COMPANION, "s", 7L, 0L, 0L, "mine_block_at", false, "stuck");
        assertEquals(LogLevel.INFO, success.level());
        assertEquals(LogEvents.TASK_FINISH, success.event());
        assertEquals(LogLevel.ERROR, failure.level());
        assertEquals(LogEvents.TASK_FINISH, failure.event());
    }

    @Test
    void queueBlockReportsThePlanAndTheRemainingCount() {
        LogRecord record = TaskLogEvents.queueBlock(OWNER, COMPANION, "s", 3L, 9L, "mine_block_at", "blocked", 2);
        assertEquals(LogEvents.QUEUE_BLOCK, record.event());
        assertEquals(LogCategory.QUEUE, record.category());
        assertEquals("2", record.data().get("remaining"));
        assertEquals(3L, record.plan());
        assertEquals(9L, record.job());
    }

    @Test
    void cancelRestoreAndRejectKeepTheirEvents() {
        assertEquals(
                LogEvents.TASK_CANCEL,
                TaskLogEvents.cancel(OWNER, COMPANION, "s", 7L, "mine_block_at", "owner stop")
                        .event());
        assertEquals(
                LogEvents.TASK_RESTORE,
                TaskLogEvents.restore(OWNER, COMPANION, "s", "restored 2 jobs").event());
        assertEquals(
                LogEvents.TASK_REJECT,
                TaskLogEvents.reject(OWNER, COMPANION, "s", "unknown tool").event());
    }

    @Test
    void anEmptyToolNameIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> TaskLogEvents.start(OWNER, COMPANION, "s", 1L, 0L, 0L, " "));
    }
}
