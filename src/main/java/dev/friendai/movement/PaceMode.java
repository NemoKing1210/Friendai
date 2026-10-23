package dev.friendai.movement;

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

    /** Parses a label, falling back to {@link #AUTO} for anything unknown. */
    public static PaceMode parse(String text) {
        if (text == null) {
            return AUTO;
        }
        return switch (text.toLowerCase(Locale.ROOT)) {
            case "walk" -> WALK;
            case "run" -> RUN;
            default -> AUTO;
        };
    }
}
