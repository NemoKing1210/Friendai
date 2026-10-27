package dev.friendai.build;

import java.util.Locale;
import java.util.Optional;

/** The parts a blueprint can be understood as. Text ids are LLM-facing and stable. */
public enum PartKind {
    FLOOR,
    WALLS,
    ROOF,
    ENTRANCE,
    WINDOW,
    INTERIOR,
    OTHER;

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Parses a name in any case; empty for anything unknown. */
    public static Optional<PartKind> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String normalized = text.trim().toLowerCase(Locale.ROOT);
        for (PartKind kind : values()) {
            if (kind.id().equals(normalized)) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }
}
