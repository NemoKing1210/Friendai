package dev.friendai.features.navigation.movement;

import java.util.Locale;

/** How the companion chooses between walking and running. */
public enum PaceMode {
    AUTO,
    WALK,
    RUN;

    /** Lower-case name, as the LLM and the configuration use it. */
    public String label() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Parses a label, falling back to {@link #AUTO} for anything unknown; a config file may carry extra spaces. */
    public static PaceMode parse(String text) {
        if (text == null) {
            return AUTO;
        }
        return switch (text.trim().toLowerCase(Locale.ROOT)) {
            case "walk" -> WALK;
            case "run" -> RUN;
            default -> AUTO;
        };
    }

    /**
     * Parses a label a caller already promised is valid. A tool refuses an unknown mode instead of silently walking:
     * the model asked for something the schema does not offer, and a wrong pace is worse than an error it can fix.
     */
    public static PaceMode parseStrict(String text) {
        if (text == null) {
            throw new IllegalArgumentException("pace mode is required");
        }
        return switch (text.trim().toLowerCase(Locale.ROOT)) {
            case "auto" -> AUTO;
            case "walk" -> WALK;
            case "run" -> RUN;
            default -> throw new IllegalArgumentException("unknown pace mode: " + text);
        };
    }
}
