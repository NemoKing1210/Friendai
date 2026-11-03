package dev.friendai.execution.log;

import dev.friendai.foundation.diagnostics.LogContext;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One nested activity inside a task activation: a composed task starting a child, a search step, a craft. The span
 * carries its own immutable context (execution, span id, enclosing span), publishes exactly one start and one end,
 * and keeps no entity or task reference, so a deferred publication is safe.
 */
public final class TaskLogSpan {
    /** Where a span publishes its pair; the server-side controller implements it. */
    public interface Sink {
        void start(TaskLogSpan span);

        void end(TaskLogSpan span, String status, String reason, Map<String, String> facts);

        /** A span that reports nothing, used when depth is exhausted. */
        Sink NONE = new Sink() {
            @Override
            public void start(TaskLogSpan span) {}

            @Override
            public void end(TaskLogSpan span, String status, String reason, Map<String, String> facts) {}
        };
    }

    private final LogContext context;
    private final String operation;
    private final String reason;
    private final Map<String, String> startFacts;
    private final Sink sink;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean finished = new AtomicBoolean();

    public TaskLogSpan(LogContext context, String operation, String reason, Map<String, String> facts, Sink sink) {
        this.context = context == null ? LogContext.NONE : context;
        this.operation = operation == null ? "" : operation;
        this.reason = reason == null ? "" : reason;
        this.startFacts = facts == null ? Map.of() : Map.copyOf(facts);
        this.sink = sink == null ? Sink.NONE : sink;
    }

    /** A detached span for a caller that has no active execution or has exhausted the depth budget. */
    public static TaskLogSpan none(String operation) {
        return new TaskLogSpan(LogContext.NONE, operation, "", Map.of(), Sink.NONE);
    }

    public LogContext context() {
        return context;
    }

    public long spanId() {
        return context.span();
    }

    public String operation() {
        return operation;
    }

    public String reason() {
        return reason;
    }

    public Map<String, String> startFacts() {
        return startFacts;
    }

    /** Publishes the opening fact once; a second call is a no-op. */
    public TaskLogSpan start() {
        if (started.compareAndSet(false, true)) {
            sink.start(this);
        }
        return this;
    }

    /** Publishes the closing fact once; a second call, or a finish after a crash cleanup, is a no-op. */
    public void finish(String status, String reason, Map<String, String> facts) {
        if (!finished.compareAndSet(false, true)) return;
        sink.end(this, status == null ? "unknown" : status, reason == null ? "" : reason, facts);
    }

    public boolean finished() {
        return finished.get();
    }
}
