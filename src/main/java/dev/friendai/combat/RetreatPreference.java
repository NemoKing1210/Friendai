package dev.friendai.combat;

import java.util.Locale;

/** How eagerly the companion disengages. It never overrides an immediate survival response. */
public enum RetreatPreference {
    /** Disengage only for the validated low-health or lethal-danger thresholds. */
    STANDARD,
    /** Disengage earlier when a safe retreat exists. */
    CONSERVATIVE;

    public String label() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static RetreatPreference parseStrict(String text) {
        if (text == null) {
            throw new IllegalArgumentException("missing retreat preference");
        }
        return switch (text.toLowerCase(Locale.ROOT)) {
            case "standard" -> STANDARD;
            case "conservative" -> CONSERVATIVE;
            default -> throw new IllegalArgumentException("unknown retreat preference: " + text);
        };
    }

    public static RetreatPreference parseOrDefault(String text) {
        try {
            return parseStrict(text);
        } catch (IllegalArgumentException e) {
            return STANDARD;
        }
    }
}
