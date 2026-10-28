package dev.friendai.features.combat;

import java.util.Locale;

/**
 * Whom the companion protects on its own initiative. Physical danger is handled separately: even
 * {@link #SELF_ONLY} may evade a projectile that threatens the companion.
 */
public enum ProtectionPolicy {
    /** Defend the companion only. An attack aimed solely at the owner is ignored. */
    SELF_ONLY,
    /** Defend the companion and the owner; the behaviour of the old defensive mode. */
    BODYGUARD,
    /** Defend both and proactively engage permitted hostile mobs near the protected anchor. */
    GUARD,
    /** Never initiate automatic damage; evade, block and retreat for survival. */
    AVOID_COMBAT;

    /** Lower-case label used by tools, save data and the status snapshot. */
    public String label() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Strict parse: an unknown label is a caller error, never a silent default. */
    public static ProtectionPolicy parseStrict(String text) {
        if (text == null) {
            throw new IllegalArgumentException("missing protection policy");
        }
        return switch (text.toLowerCase(Locale.ROOT)) {
            case "self_only" -> SELF_ONLY;
            case "bodyguard" -> BODYGUARD;
            case "guard" -> GUARD;
            case "avoid_combat" -> AVOID_COMBAT;
            default -> throw new IllegalArgumentException("unknown protection policy: " + text);
        };
    }

    /** Lenient parse for absent or corrupted save data: the documented safe default. */
    public static ProtectionPolicy parseOrDefault(String text) {
        try {
            return parseStrict(text);
        } catch (IllegalArgumentException e) {
            return BODYGUARD;
        }
    }

    /** True when the protection preset permits attacking an aggressor of the owner. */
    public boolean defendsOwner() {
        return this == BODYGUARD || this == GUARD;
    }

    /** True when hostile mobs near the anchor may be engaged without provocation. */
    public boolean guardsArea() {
        return this == GUARD;
    }
}
