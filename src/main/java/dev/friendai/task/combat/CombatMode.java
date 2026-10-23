package dev.friendai.task.combat;

import java.util.Locale;

/** How the companion reacts to threats. */
public enum CombatMode {
    DEFENSIVE,
    GUARD,
    PASSIVE;

    /** Lower-case name, as the LLM and the configuration use it. */
    public String label() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Parses a label, falling back to {@link #DEFENSIVE} for anything unknown. */
    public static CombatMode parse(String text) {
        if (text == null) {
            return DEFENSIVE;
        }
        return switch (text.toLowerCase(Locale.ROOT)) {
            case "guard" -> GUARD;
            case "passive" -> PASSIVE;
            default -> DEFENSIVE;
        };
    }
}
