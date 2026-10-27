package dev.friendai.combat;

import java.util.Locale;

/** How the companion prefers to fight. A style scores actions; it never grants equipment or permissions. */
public enum FightingStyle {
    /** Best safe action for the current equipment and threat. */
    BALANCED,
    /** Block, keep distance and accept fewer attack windows. */
    CAUTIOUS,
    /** Close to a useful melee range when that stays safe. */
    MELEE,
    /** Use a bow at a useful distance when ammunition and a clear lane exist. */
    RANGED;

    public String label() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static FightingStyle parseStrict(String text) {
        if (text == null) {
            throw new IllegalArgumentException("missing fighting style");
        }
        return switch (text.toLowerCase(Locale.ROOT)) {
            case "balanced" -> BALANCED;
            case "cautious" -> CAUTIOUS;
            case "melee" -> MELEE;
            case "ranged" -> RANGED;
            default -> throw new IllegalArgumentException("unknown fighting style: " + text);
        };
    }

    public static FightingStyle parseOrDefault(String text) {
        try {
            return parseStrict(text);
        } catch (IllegalArgumentException e) {
            return BALANCED;
        }
    }
}
