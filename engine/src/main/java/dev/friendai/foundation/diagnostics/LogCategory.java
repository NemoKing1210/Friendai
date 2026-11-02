package dev.friendai.foundation.diagnostics;

import java.util.Locale;
import java.util.Optional;

/** The subsystem a structured log record belongs to. */
public enum LogCategory {
    BRAIN,
    TOOL,
    TASK,
    QUEUE,
    NET,
    SPEECH,
    WORLD,
    CONFIG,
    CLIENT,
    SERVER;

    /** The lower-case token used in files, filters and settings keys. */
    public String token() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<LogCategory> parse(String value) {
        if (value == null || value.isBlank()) return Optional.empty();
        for (LogCategory category : values()) {
            if (category.name().equalsIgnoreCase(value.strip())) return Optional.of(category);
        }
        return Optional.empty();
    }
}
