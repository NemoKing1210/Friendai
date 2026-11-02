package dev.friendai.foundation.diagnostics;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Assigns the sequence, redacts once and fans a record out: the ring always, file sinks only when the current
 * settings allow the record. Filtering here keeps every producer free of policy checks.
 */
public final class LogPipeline {
    private final LogSink ring;
    private final Supplier<LogSettings> settings;
    private final UnaryOperator<String> redactor;
    private final List<LogSink> files;
    private final AtomicLong sequence = new AtomicLong();

    public LogPipeline(LogSink ring, Supplier<LogSettings> settings, UnaryOperator<String> redactor, LogSink... files) {
        this.ring = ring;
        this.settings = settings;
        this.redactor = redactor == null ? text -> text : redactor;
        this.files = files == null ? List.of() : List.of(files);
    }

    /** Sends one draft and returns the sequence it was given. */
    public long emit(LogRecord draft) {
        LogRecord redacted = redact(draft);
        long assigned = sequence.incrementAndGet();
        LogRecord record = new LogRecord(
                assigned,
                redacted.timeMillis() == 0L ? System.currentTimeMillis() : redacted.timeMillis(),
                redacted.level(),
                redacted.category(),
                redacted.kind(),
                redacted.event(),
                redacted.owner(),
                redacted.companion(),
                redacted.session(),
                redacted.turn(),
                redacted.plan(),
                redacted.job(),
                redacted.task(),
                redacted.toolCall(),
                redacted.cause(),
                redacted.durationMs(),
                redacted.message(),
                redacted.data(),
                redacted.error());
        ring.accept(record);
        LogSettings current = settings.get();
        if (current != null && current.allows(record)) {
            for (LogSink sink : files) {
                sink.accept(record);
            }
        }
        return assigned;
    }

    /** The settings are read on every record, so an update takes effect immediately; kept for callers. */
    public void update(LogSettings ignored) {}

    private LogRecord redact(LogRecord record) {
        Map<String, String> data = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : record.data().entrySet()) {
            data.put(redactor.apply(entry.getKey()), redactor.apply(entry.getValue()));
        }
        LogError error = record.error();
        if (error != null) {
            error = new LogError(
                    redactor.apply(error.type()),
                    redactor.apply(error.message()),
                    redactor.apply(error.stack()),
                    error.frames());
        }
        return new LogRecord(
                record.seq(),
                record.timeMillis(),
                record.level(),
                record.category(),
                record.kind(),
                redactor.apply(record.event()),
                record.owner(),
                record.companion(),
                redactor.apply(record.session()),
                record.turn(),
                record.plan(),
                record.job(),
                record.task(),
                redactor.apply(record.toolCall()),
                record.cause(),
                record.durationMs(),
                redactor.apply(record.message()),
                data,
                error);
    }
}
