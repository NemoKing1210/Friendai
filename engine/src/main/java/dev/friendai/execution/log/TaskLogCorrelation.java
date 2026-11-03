package dev.friendai.execution.log;

import dev.friendai.foundation.diagnostics.LogEvents;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Resolves the symbolic parents of deferred task lifecycle facts to assigned sequences.
 *
 * <p>A start fact may be published after the work it describes has already been queued, and a phase emitted from
 * {@code task.start} follows its start only because this resolver remembers the start's sequence. It is bounded
 * bookkeeping, not an event store: at most {@value #MAX_EXECUTIONS} live executions and
 * {@value #MAX_SPANS_PER_EXECUTION} open spans per execution are remembered, the oldest execution is evicted first,
 * and an evicted parent resolves to {@link #UNRESOLVED} so the record says so instead of guessing.
 */
public final class TaskLogCorrelation {
    public static final int MAX_EXECUTIONS = 32;
    public static final int MAX_SPANS_PER_EXECUTION = 8;
    public static final long UNRESOLVED = -1L;

    private final Map<Long, Long> executionStarts = new LinkedHashMap<>();
    private final Map<String, Long> spanStarts = new LinkedHashMap<>();

    /** The sequence the event descends from, or {@link #UNRESOLVED} when the parent is unknown or evicted. */
    public long parentSeq(TaskLogEvent event) {
        if (event == null) return UNRESOLVED;
        return switch (event.parent()) {
            case INVOCATION -> event.context().cause() > 0L ? event.context().cause() : UNRESOLVED;
            case EXECUTION -> executionStarts.getOrDefault(event.context().execution(), UNRESOLVED);
            case SPAN -> {
                // A span end descends from its own start; any other span fact descends from the enclosing span.
                long referenced = LogEvents.SPAN_END.equals(event.event())
                        ? event.context().span()
                        : event.context().parentSpan();
                yield spanStarts.getOrDefault(key(event.context().execution(), referenced), UNRESOLVED);
            }
        };
    }

    /** Remembers the sequence a start fact was given, so later facts can name it as their parent. */
    public void recordStart(TaskLogEvent event, long assignedSeq) {
        if (event == null || assignedSeq <= 0L) return;
        long execution = event.context().execution();
        if (execution <= 0L) return;
        if (LogEvents.SPAN_START.equals(event.event())) {
            String key = key(execution, event.context().span());
            spanStarts.remove(key);
            spanStarts.put(key, assignedSeq);
            while (spanStarts.size() > MAX_EXECUTIONS * MAX_SPANS_PER_EXECUTION) {
                Iterator<String> oldest = spanStarts.keySet().iterator();
                oldest.next();
                oldest.remove();
            }
            trimSpans(execution);
        } else if (LogEvents.TASK_START.equals(event.event())) {
            executionStarts.remove(execution);
            executionStarts.put(execution, assignedSeq);
            while (executionStarts.size() > MAX_EXECUTIONS) {
                Iterator<Long> oldest = executionStarts.keySet().iterator();
                Long evicted = oldest.next();
                oldest.remove();
                dropSpans(evicted);
            }
        }
    }

    /** Releases the execution only after its terminal fact: children that close later still resolve their parent. */
    public void recordTerminal(TaskLogEvent event) {
        if (event == null) return;
        long execution = event.context().execution();
        if (execution <= 0L) return;
        if (LogEvents.SPAN_END.equals(event.event())) {
            spanStarts.remove(key(execution, event.context().span()));
        } else if (event.terminal()) {
            dropSpans(execution);
            executionStarts.remove(execution);
        }
    }

    public int liveExecutions() {
        return executionStarts.size();
    }

    public int openSpans() {
        return spanStarts.size();
    }

    public void clear() {
        executionStarts.clear();
        spanStarts.clear();
    }

    private void trimSpans(long execution) {
        int count = 0;
        for (String key : spanStarts.keySet()) {
            if (key.startsWith(execution + ":")) count++;
        }
        while (count > MAX_SPANS_PER_EXECUTION) {
            Iterator<String> iterator = spanStarts.keySet().iterator();
            while (iterator.hasNext()) {
                String key = iterator.next();
                if (key.startsWith(execution + ":")) {
                    iterator.remove();
                    count--;
                    break;
                }
            }
        }
    }

    private void dropSpans(long execution) {
        spanStarts.keySet().removeIf(key -> key.startsWith(execution + ":"));
    }

    private static String key(long execution, long span) {
        return execution + ":" + span;
    }
}
