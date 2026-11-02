package dev.friendai.foundation.diagnostics;

import java.util.Locale;
import java.util.Optional;

/** How loud a structured log record is. */
public enum LogLevel {
    TRACE,
    DEBUG,
    INFO,
    WARN,
    ERROR;

    public boolean atLeast(LogLevel other) {
        return other != null && ordinal() >= other.ordinal();
    }

    /** The lower-case token used in files and command arguments. */
    public String token() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<LogLevel> parse(String value) {
        if (value == null || value.isBlank()) return Optional.empty();
        for (LogLevel level : values()) {
            if (level.name().equalsIgnoreCase(value.strip())) return Optional.of(level);
        }
        return Optional.empty();
    }
}
