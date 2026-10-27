package dev.friendai.combat;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Persistent owner preferences for companion combat. Immutable; a malformed save is decoded strictly and
 * falls back to {@link #defaults()} as one whole object, so a valid field never survives next to a bad one.
 *
 * <p>{@link #encode()} is the save format. It is deliberately a small key/value string rather than entity
 * fields, so one missing or unparsable value rejects the whole record instead of mixing defaults into it.
 */
public record CombatSettings(
        ProtectionPolicy protection,
        FightingStyle style,
        boolean allowSplashPotions,
        int pursuitLimitBlocks,
        RetreatPreference retreatPreference) {
    public static final int MIN_PURSUIT = 8;
    public static final int MAX_PURSUIT = 64;
    /** The old {@code FIGHT_DROPOFF} bound, kept as the default so migration changes nothing. */
    public static final int DEFAULT_PURSUIT = 24;

    public CombatSettings {
        protection = Objects.requireNonNull(protection, "protection");
        style = Objects.requireNonNull(style, "style");
        retreatPreference = Objects.requireNonNull(retreatPreference, "retreatPreference");
        if (pursuitLimitBlocks < MIN_PURSUIT || pursuitLimitBlocks > MAX_PURSUIT) {
            throw new IllegalArgumentException(
                    "pursuit limit must be " + MIN_PURSUIT + ".." + MAX_PURSUIT + ", was " + pursuitLimitBlocks);
        }
    }

    /** Current defaults: bodyguard and balanced, no potions, the old 24-block bound, standard retreat. */
    public static CombatSettings defaults() {
        return new CombatSettings(
                ProtectionPolicy.BODYGUARD, FightingStyle.BALANCED, false, DEFAULT_PURSUIT, RetreatPreference.STANDARD);
    }

    public CombatSettings withProtection(ProtectionPolicy value) {
        return new CombatSettings(value, style, allowSplashPotions, pursuitLimitBlocks, retreatPreference);
    }

    public CombatSettings withStyle(FightingStyle value) {
        return new CombatSettings(protection, value, allowSplashPotions, pursuitLimitBlocks, retreatPreference);
    }

    public CombatSettings withSplashPotions(boolean value) {
        return new CombatSettings(protection, style, value, pursuitLimitBlocks, retreatPreference);
    }

    public CombatSettings withPursuitLimit(int value) {
        return new CombatSettings(protection, style, allowSplashPotions, value, retreatPreference);
    }

    public CombatSettings withRetreatPreference(RetreatPreference value) {
        return new CombatSettings(protection, style, allowSplashPotions, pursuitLimitBlocks, value);
    }

    public String encode() {
        return "protection=" + protection.label()
                + ";style=" + style.label()
                + ";splashPotions=" + allowSplashPotions
                + ";pursuit=" + pursuitLimitBlocks
                + ";retreat=" + retreatPreference.label();
    }

    /** Strict decode; a missing, unknown or duplicate field rejects the whole object. */
    public static CombatSettings decode(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("missing combat settings");
        }
        Map<String, String> values = new HashMap<>();
        for (String part : text.split(";", -1)) {
            int eq = part.indexOf('=');
            if (eq <= 0 || eq == part.length() - 1) {
                throw new IllegalArgumentException("malformed combat settings field: " + part);
            }
            String key = part.substring(0, eq);
            String value = part.substring(eq + 1);
            if (values.put(key, value) != null) {
                throw new IllegalArgumentException("duplicate combat settings field: " + key);
            }
        }
        if (values.size() != 5) {
            throw new IllegalArgumentException("combat settings need five fields, got " + values.size());
        }
        return new CombatSettings(
                ProtectionPolicy.parseStrict(require(values, "protection")),
                FightingStyle.parseStrict(require(values, "style")),
                parseBoolean(require(values, "splashPotions")),
                parsePursuit(require(values, "pursuit")),
                RetreatPreference.parseStrict(require(values, "retreat")));
    }

    /** Lenient decode for save loading: any failure selects the documented defaults. */
    public static CombatSettings decodeOrDefault(String text) {
        try {
            return decode(text);
        } catch (IllegalArgumentException e) {
            return defaults();
        }
    }

    private static String require(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null) {
            throw new IllegalArgumentException("missing combat settings field: " + key);
        }
        return value;
    }

    private static boolean parseBoolean(String text) {
        return switch (text) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException("bad boolean: " + text);
        };
    }

    private static int parsePursuit(String text) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("bad pursuit limit: " + text);
        }
    }
}
