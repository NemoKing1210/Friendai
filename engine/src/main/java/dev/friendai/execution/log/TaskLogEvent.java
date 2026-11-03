package dev.friendai.execution.log;

import dev.friendai.foundation.diagnostics.LogContext;
import dev.friendai.foundation.diagnostics.LogEvents;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One immutable diagnostic lifecycle fact about a task activation: which execution it belongs to, which parent it
 * descends from, and bounded metadata. It never holds an entity or a task reference, so a deferred publication can
 * safely carry it.
 *
 * <p>{@link Parent#INVOCATION} descends from the captured cause (the tool call or the queue activation);
 * {@link Parent#EXECUTION} from this execution's own start, which may not have a sequence yet when the fact is
 * created; {@link Parent#SPAN} from an encloses span.
 */
public record TaskLogEvent(
        LogContext context,
        Parent parent,
        String event,
        String tool,
        String status,
        long durationMs,
        Map<String, String> facts) {

    public enum Parent {
        INVOCATION,
        EXECUTION,
        SPAN
    }

    public TaskLogEvent {
        context = context == null ? LogContext.NONE : context;
        parent = parent == null ? Parent.INVOCATION : parent;
        event = event == null ? "" : event;
        tool = tool == null ? "" : tool;
        status = status == null ? "" : status;
        facts = facts == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(facts));
    }

    /** True for an event that opens an execution or a span and therefore registers a start sequence. */
    public boolean starts() {
        return LogEvents.TASK_START.equals(event) || LogEvents.SPAN_START.equals(event);
    }

    /** True for an event that closes an execution or a span. */
    public boolean terminal() {
        return LogEvents.TASK_FINISH.equals(event)
                || LogEvents.TASK_CANCEL.equals(event)
                || LogEvents.TASK_REJECT.equals(event)
                || LogEvents.SPAN_END.equals(event);
    }

    public boolean span() {
        return LogEvents.SPAN_START.equals(event) || LogEvents.SPAN_END.equals(event);
    }
}
