package dev.friendai.server.execution;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Serializes task events for one companion: an event raised inside a transition is queued and flushed once the
 * transition settles, so a listener can never reenter a half-finished queue update. A notice raised before a
 * sink is attached is kept (bounded) and replayed when it arrives.
 */
final class TaskEventDispatcher {
    private TaskEventSink sink;
    private final ArrayDeque<Consumer<TaskEventSink>> pending = new ArrayDeque<>();
    private final List<String> pendingNotices = new ArrayList<>();
    private int transitionDepth;
    private boolean flushing;

    /** Attaches the sink, replaying every notice that was raised before it existed. */
    void sink(TaskEventSink eventSink) {
        this.sink = eventSink;
        if (eventSink != null) {
            for (String notice : pendingNotices) emit(target -> target.onNotice(notice));
            pendingNotices.clear();
        }
    }

    /** Delivers now, or queues until the current transition settles when one is open. */
    void emit(Consumer<TaskEventSink> event) {
        if (sink == null) return;
        if (transitionDepth > 0 || flushing) {
            pending.addLast(event);
            return;
        }
        event.accept(sink);
    }

    /** A notice raised before the sink exists, replayed when it attaches; bounded at 16. */
    void notice(String text) {
        if (sink != null) {
            emit(target -> target.onNotice(text));
        } else if (text != null) {
            if (pendingNotices.size() == 16) pendingNotices.removeFirst();
            pendingNotices.add(text);
        }
    }

    <T> T inTransition(Supplier<T> action) {
        transitionDepth++;
        try {
            return action.get();
        } finally {
            transitionDepth--;
            if (transitionDepth == 0) flush();
        }
    }

    void inTransition(Runnable action) {
        inTransition(() -> {
            action.run();
            return null;
        });
    }

    /** True once a sink is attached; an event raised before it is not delivered anywhere. */
    boolean hasSink() {
        return sink != null;
    }

    /** Flushes anything queued by a transition that was opened outside {@link #inTransition}. */
    void flush() {
        if (flushing || sink == null) return;
        flushing = true;
        try {
            while (!pending.isEmpty()) pending.removeFirst().accept(sink);
        } finally {
            flushing = false;
        }
    }
}
