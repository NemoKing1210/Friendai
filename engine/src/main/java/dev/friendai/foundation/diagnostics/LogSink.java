package dev.friendai.foundation.diagnostics;

/** A destination for structured records: the in-memory ring, one file writer, or a test double. */
public interface LogSink {
    void accept(LogRecord record);
}
