package dev.friendai.features.memory;

import java.util.List;

/**
 * A fact that could not be written, with a kind the menu can translate. The message stays the one written
 * for the model, so tool results read exactly as they did before the menu existed.
 */
public final class MemoryLimitException extends IllegalArgumentException {
    public enum Kind {
        EMPTY,
        TOO_LONG,
        FULL,
        DUPLICATE,
        NO_FACT
    }

    private final Kind kind;
    private final List<Object> values;

    public MemoryLimitException(Kind kind, String message, List<Object> values) {
        super(message);
        this.kind = kind;
        this.values = List.copyOf(values);
    }

    public Kind kind() {
        return kind;
    }

    /** What the message needs: {@code []}, {@code [length, max]}, {@code [subject, max]}, {@code [id]}. */
    public List<Object> values() {
        return values;
    }
}
