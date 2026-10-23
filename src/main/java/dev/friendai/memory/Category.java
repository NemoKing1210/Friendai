package dev.friendai.memory;

import java.util.Locale;
import java.util.Optional;

/** The fixed taxonomy of remembered facts. */
public enum Category {
    PROFILE,
    PREFERENCE,
    PLACE,
    PROMISE,
    NOTE;

    /** Case-insensitive lookup; empty for an unknown or missing name. */
    public static Optional<Category> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String name = raw.strip().toUpperCase(Locale.ROOT);
        for (Category category : values()) {
            if (category.name().equals(name)) {
                return Optional.of(category);
            }
        }
        return Optional.empty();
    }
}
