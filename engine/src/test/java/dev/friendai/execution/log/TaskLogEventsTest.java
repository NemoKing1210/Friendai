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
    void contextualFactsCannotForgePhaseOrGapFields() {
        var context = new dev.friendai.foundation.diagnostics.LogContext("trace", 1, 3, 9, 7, 11, 2, 1, "call", 0);
        var facts = new java.util.LinkedHashMap<String, String>();
        facts.put("phase", "forged");
        facts.put("reason", "forged");
        facts.put("detail", "forged");
        facts.put("waitingTicks", "5");
        facts.put("waitingLimitTicks", "20");
        facts.put("stamp", "4");
        var phase = TaskLogEvents.phaseEvent(
                context, "mine", dev.friendai.task.TaskPhase.WAITING, -1, "waiting", "full detail", facts);
        assertEquals("task.phase", phase.event());
        assertEquals("waiting", phase.facts().get("phase"));
        assertEquals("waiting", phase.facts().get("reason"));
        assertEquals("full detail", phase.facts().get("detail"));
        assertEquals("forged", facts.get("phase"));
        var data = dev.friendai.foundation.diagnostics.LogEventData.build(context, phase.facts());
        assertEquals("5", data.get("waitingTicks"));
        assertEquals("20", data.get("waitingLimitTicks"));
        assertEquals("4", data.get("stamp"));
        assertEquals(
                "task.progress",
                TaskLogEvents.phaseEvent(
                                context, "mine", dev.friendai.task.TaskPhase.PROGRESS, -1, "running", "detail", facts)
                        .event());
        facts.put("tool", "mine");
        var gap = TaskLogEvents.clockGap(context, 45452, facts);
        assertEquals(context, gap.context());
        assertEquals("mine", gap.tool());
        assertEquals(45452, gap.durationMs());
        assertEquals("45452", gap.facts().get("gapMs"));
        assertEquals("unknown", gap.facts().get("reason"));
        assertEquals(TaskLogEvent.Parent.EXECUTION, gap.parent());
        var detached = TaskLogEvents.clockGap(45452);
        assertEquals(dev.friendai.foundation.diagnostics.LogContext.NONE, detached.context());
        assertEquals("", detached.tool());
        assertEquals(TaskLogEvent.Parent.INVOCATION, detached.parent());
        assertEquals(0, TaskLogEvents.clockGap(-1).durationMs());
    }

    @Test
    void startCarriesEveryIdentifier() {
        LogRecord record = TaskLogEvents.start(OWNER, COMPANION, "s", 7L, 3L, 9L, "mine_block_at");
        assertEquals(LogEvents.TASK_START, record.event());
        assertEquals(LogCategory.TASK, record.category());
        assertEquals(LogKind.TASK, record.kind());
        assertEquals(LogLevel.INFO, record.level());
        assertEquals(7L, record.task());
        assertEquals(0L, record.turn());
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
