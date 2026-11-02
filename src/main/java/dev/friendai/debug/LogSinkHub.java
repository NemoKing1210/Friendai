package dev.friendai.debug;

import dev.friendai.foundation.diagnostics.LogRecord;
import dev.friendai.foundation.diagnostics.LogSink;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** The process-wide set of writers the log4j bridge feeds; attach and detach as a server or client starts. */
public final class LogSinkHub {
    private static final AtomicReference<List<LogSink>> SINKS = new AtomicReference<>(List.of());

    private LogSinkHub() {}

    public static void add(LogSink sink) {
        if (sink == null) return;
        List<LogSink> current;
        List<LogSink> next;
        do {
            current = SINKS.get();
            if (current.contains(sink)) return;
            next = new ArrayList<>(current.size() + 1);
            next.addAll(current);
            next.add(sink);
        } while (!SINKS.compareAndSet(current, List.copyOf(next)));
    }

    public static void remove(LogSink sink) {
        if (sink == null) return;
        List<LogSink> current;
        List<LogSink> next;
        do {
            current = SINKS.get();
            if (!current.contains(sink)) return;
            next = new ArrayList<>(current);
            next.remove(sink);
        } while (!SINKS.compareAndSet(current, List.copyOf(next)));
    }

    /** Never throws and never logs: a broken sink must not break the logger. */
    public static void accept(LogRecord record) {
        if (record == null) return;
        for (LogSink sink : SINKS.get()) {
            try {
                sink.accept(record);
            } catch (RuntimeException ignored) {
                // A failing sink is the writer's problem, not the game's.
            }
        }
    }
}
