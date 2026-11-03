package dev.friendai.execution.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.foundation.diagnostics.LogContext;
import dev.friendai.foundation.diagnostics.LogEvents;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TaskLifecycleLogTest {
    private static LogContext executionContext(long execution, long plan, long job, long task, long cause) {
        return new LogContext("trace-1", 3L, plan, job, task, execution, 0L, 0L, "mine_block_at", cause);
    }

    @Test
    void aStartDescendsFromTheInvocationThatCausedIt() {
        TaskLogEvent start = TaskLogEvents.startEvent(executionContext(7L, 0L, 0L, 0L, 42L), "mine_block_at");

        assertEquals(LogEvents.TASK_START, start.event());
        assertEquals(TaskLogEvent.Parent.INVOCATION, start.parent());
        assertEquals(42L, new TaskLogCorrelation().parentSeq(start));
        assertTrue(start.starts());
        assertFalse(start.terminal());
        assertEquals("mine_block_at", start.tool());
        assertEquals("trace-1", start.context().trace());
    }

    @Test
    void aMissingInvocationCauseIsExplicitlyUnresolved() {
        TaskLogEvent start = TaskLogEvents.startEvent(executionContext(7L, 0L, 0L, 0L, 0L), "mine_block_at");

        assertEquals(TaskLogCorrelation.UNRESOLVED, new TaskLogCorrelation().parentSeq(start));
    }

    @Test
    void aTerminalResolvesOnlyAfterItsExecutionStartWasRecorded() {
        TaskLogCorrelation correlation = new TaskLogCorrelation();
        LogContext context = executionContext(7L, 4L, 5L, 0L, 42L);
        TaskLogEvent start = TaskLogEvents.startEvent(context, "mine_block_at");
        TaskLogEvent finish = TaskLogEvents.finishEvent(context, "mine_block_at", "success");

        assertEquals(TaskLogCorrelation.UNRESOLVED, correlation.parentSeq(finish));
        correlation.recordStart(start, 100L);
        assertEquals(100L, correlation.parentSeq(finish));
        assertFalse(finish.starts());
        assertTrue(finish.terminal());

        correlation.recordTerminal(finish);
        assertEquals(TaskLogCorrelation.UNRESOLVED, correlation.parentSeq(finish));
        assertEquals(0, correlation.liveExecutions());
    }

    @Test
    void aLateChildOfAClosedExecutionIsUnresolvedRatherThanPointingAtAReusedId() {
        TaskLogCorrelation correlation = new TaskLogCorrelation();
        LogContext first = executionContext(7L, 0L, 0L, 0L, 0L);
        correlation.recordStart(TaskLogEvents.startEvent(first, "mine_block_at"), 100L);
        correlation.recordTerminal(TaskLogEvents.finishEvent(first, "mine_block_at", "success"));
        correlation.recordStart(TaskLogEvents.startEvent(executionContext(8L, 0L, 0L, 0L, 0L), "give_items"), 200L);

        TaskLogEvent stale = TaskLogEvents.holdEvent(first, "mine_block_at");

        assertEquals(TaskLogCorrelation.UNRESOLVED, correlation.parentSeq(stale));
        assertEquals(200L, correlation.parentSeq(TaskLogEvents.holdEvent(executionContext(8L, 0L, 0L, 0L, 0L), "x")));
    }

    @Test
    void aSpanResolvesThroughItsExecutionAndClosesIndependently() {
        TaskLogCorrelation correlation = new TaskLogCorrelation();
        LogContext execution = executionContext(7L, 0L, 2L, 0L, 42L);
        correlation.recordStart(TaskLogEvents.startEvent(execution, "obtain_item"), 100L);
        LogContext span = execution.withSpan(3L, 0L, 0L);
        TaskLogEvent spanStart = new TaskLogEvent(
                span, TaskLogEvent.Parent.EXECUTION, LogEvents.SPAN_START, "mine_blocks", "started", -1L, Map.of());
        correlation.recordStart(spanStart, 110L);

        LogContext nested = execution.withSpan(4L, 3L, 0L);
        TaskLogEvent child = new TaskLogEvent(
                nested, TaskLogEvent.Parent.SPAN, LogEvents.TASK_PROGRESS, "mine_blocks", "running", -1L, Map.of());
        assertEquals(110L, correlation.parentSeq(child));

        correlation.recordTerminal(new TaskLogEvent(
                span, TaskLogEvent.Parent.SPAN, LogEvents.SPAN_END, "mine_blocks", "ok", -1L, Map.of()));
        assertEquals(TaskLogCorrelation.UNRESOLVED, correlation.parentSeq(child));
        assertEquals(100L, correlation.parentSeq(TaskLogEvents.holdEvent(execution, "obtain_item")));
    }

    @Test
    void theResolverIsBoundedAndEvictsTheOldestExecutionFirst() {
        TaskLogCorrelation correlation = new TaskLogCorrelation();
        for (long execution = 1; execution <= TaskLogCorrelation.MAX_EXECUTIONS + 3; execution++) {
            correlation.recordStart(
                    TaskLogEvents.startEvent(executionContext(execution, 0L, 0L, 0L, 0L), "mine_block_at"),
                    execution * 10L);
        }

        assertEquals(TaskLogCorrelation.MAX_EXECUTIONS, correlation.liveExecutions());
        assertEquals(
                TaskLogCorrelation.UNRESOLVED,
                correlation.parentSeq(TaskLogEvents.holdEvent(executionContext(1L, 0L, 0L, 0L, 0L), "x")));
        long newest = TaskLogCorrelation.MAX_EXECUTIONS + 3;
        assertEquals(
                newest * 10L,
                correlation.parentSeq(TaskLogEvents.holdEvent(executionContext(newest, 0L, 0L, 0L, 0L), "x")));
    }

    @Test
    void openSpansPerExecutionAreBounded() {
        TaskLogCorrelation correlation = new TaskLogCorrelation();
        LogContext execution = executionContext(9L, 0L, 0L, 0L, 0L);
        correlation.recordStart(TaskLogEvents.startEvent(execution, "obtain_item"), 100L);
        for (long span = 1; span <= TaskLogCorrelation.MAX_SPANS_PER_EXECUTION + 2; span++) {
            correlation.recordStart(
                    new TaskLogEvent(
                            execution.withSpan(span, 0L, 0L),
                            TaskLogEvent.Parent.EXECUTION,
                            LogEvents.SPAN_START,
                            "mine_blocks",
                            "started",
                            -1L,
                            Map.of()),
                    200L + span);
        }

        assertTrue(correlation.openSpans() <= TaskLogCorrelation.MAX_SPANS_PER_EXECUTION);
        assertEquals(correlation.openSpans(), correlation.openSpans());
    }

    @Test
    void aStartCanNameItsOriginAndAClockGapStaysUnknown() {
        TaskLogEvent restored = TaskLogEvents.startEvent(
                executionContext(1L, 0L, 0L, 0L, 0L), "mine_block_at", Map.of("origin", "restore"));
        assertEquals("restore", restored.facts().get("origin"));

        TaskLogEvent gap = TaskLogEvents.clockGap(2_500L);
        assertEquals(LogEvents.CLOCK_GAP, gap.event());
        assertEquals("unknown", gap.facts().get("reason"));
        assertEquals(2_500L, gap.durationMs());
        assertEquals("2500", gap.facts().get("gapMs"));
    }

    @Test
    void aPhaseFactCarriesOnlyWhatTheTaskActuallyMeasured() {
        TaskLogEvent phase = TaskLogEvents.phaseEvent(
                executionContext(3L, 0L, 0L, 0L, 9L),
                "mine_block_at",
                dev.friendai.task.TaskPhase.PROGRESS,
                40,
                "search_selected",
                "mining iron");
        assertEquals(LogEvents.TASK_PROGRESS, phase.event());
        assertEquals(TaskLogEvent.Parent.EXECUTION, phase.parent());
        assertEquals("40", phase.facts().get("percent"));
        assertEquals("search_selected", phase.facts().get("reason"));

        TaskLogEvent unknown = TaskLogEvents.phaseEvent(
                executionContext(3L, 0L, 0L, 0L, 9L), "mine_block_at", dev.friendai.task.TaskPhase.WAITING, -1, "", "");
        assertEquals(LogEvents.TASK_PHASE, unknown.event());
        assertFalse(unknown.facts().containsKey("percent"), "an unknown percent stays absent, not zero");

        TaskLogEvent snapshot = TaskLogEvents.snapshotEvent(
                executionContext(3L, 0L, 0L, 0L, 9L), "mine_block_at", "stalled", Map.of("activeTicks", "200"));
        assertEquals(LogEvents.TASK_SNAPSHOT, snapshot.event());
        assertEquals("stalled", snapshot.facts().get("reason"));
        assertEquals("200", snapshot.facts().get("activeTicks"));
    }

    @Test
    void factsAreAnImmutableBoundedCopy() {
        Map<String, String> facts = new HashMap<>();
        facts.put("reason", "search_selected");
        TaskLogEvent event = new TaskLogEvent(
                executionContext(1L, 0L, 0L, 0L, 0L),
                TaskLogEvent.Parent.INVOCATION,
                LogEvents.RESOURCE_DECISION,
                "mine_block_at",
                "selected",
                -1L,
                facts);
        facts.put("reason", "mutated");

        assertEquals("search_selected", event.facts().get("reason"));
        assertThrows(UnsupportedOperationException.class, () -> event.facts().put("x", "y"));
    }
}
