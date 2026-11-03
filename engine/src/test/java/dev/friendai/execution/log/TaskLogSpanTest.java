package dev.friendai.execution.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.foundation.diagnostics.LogContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TaskLogSpanTest {
    private static final LogContext CONTEXT = new LogContext("trace-1", 2L, 0L, 0L, 0L, 7L, 3L, 0L, "obtain_item", 9L);

    private static final class RecordingSink implements TaskLogSpan.Sink {
        final List<String> events = new ArrayList<>();
        final List<TaskLogSpan> spans = new ArrayList<>();

        @Override
        public void start(TaskLogSpan span) {
            events.add("start:" + span.operation());
            spans.add(span);
        }

        @Override
        public void end(TaskLogSpan span, String status, String reason, Map<String, String> facts) {
            events.add("end:" + status + ":" + reason + ":" + facts.getOrDefault("detail", ""));
        }
    }

    @Test
    void aSpanPublishesExactlyOneStartAndOneEnd() {
        RecordingSink sink = new RecordingSink();
        TaskLogSpan span = new TaskLogSpan(CONTEXT, "mine", "obtain_step", Map.of("step", "1"), sink);

        span.start();
        span.start();
        span.finish("success", "child_succeeded", Map.of("detail", "mined"));
        span.finish("failed", "child_failed", Map.of("detail", "late"));

        assertEquals(List.of("start:mine", "end:success:child_succeeded:mined"), sink.events);
        assertTrue(span.finished());
        assertEquals(3L, span.spanId());
        assertEquals(CONTEXT, span.context());
    }

    @Test
    void aDetachedSpanReportsNothing() {
        RecordingSink sink = new RecordingSink();
        TaskLogSpan span = TaskLogSpan.none("mine");

        span.start();
        span.finish("success", "done", Map.of());

        assertTrue(sink.events.isEmpty());
        assertEquals(0L, span.spanId());
        assertTrue(span.finished());
        assertFalse(span.startFacts().containsKey("step"));
    }

    @Test
    void theStartEventNamesTheEnclosingSpanOrExecution() {
        TaskLogEvent topLevel = TaskLogEvents.spanStart(CONTEXT, "mine", "obtain_step", Map.of());
        assertEquals(TaskLogEvent.Parent.EXECUTION, topLevel.parent());
        assertEquals("mine", topLevel.facts().get("operation"));

        LogContext nested = CONTEXT.withSpan(4L, 3L, 0L);
        TaskLogEvent inner = TaskLogEvents.spanStart(nested, "craft", "", Map.of());
        assertEquals(TaskLogEvent.Parent.SPAN, inner.parent());
    }

    @Test
    void aSpanEndDescendsFromItsOwnStart() {
        TaskLogCorrelation correlation = new TaskLogCorrelation();
        correlation.recordStart(TaskLogEvents.spanStart(CONTEXT, "mine", "", Map.of()), 110L);

        TaskLogEvent end = TaskLogEvents.spanEnd(CONTEXT, "mine", "success", "child_succeeded", Map.of());

        assertEquals(110L, correlation.parentSeq(end));
        correlation.recordTerminal(end);
        assertEquals(TaskLogCorrelation.UNRESOLVED, correlation.parentSeq(end));
    }
}
