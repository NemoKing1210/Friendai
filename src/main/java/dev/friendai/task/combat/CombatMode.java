package dev.friendai.task.combat;

import dev.friendai.combat.CombatSettings;
import dev.friendai.combat.FightingStyle;
import dev.friendai.combat.ProtectionPolicy;
import java.util.Locale;

/**
 * Legacy combined combat mode kept for old save data and the compatibility tool. New code reads
 * {@link CombatSettings}; this enum only maps the three old presets to the split protection/style settings.
 */
public enum CombatMode {
    DEFENSIVE,
    GUARD,
    PASSIVE;

    /** Lower-case name, as the old tool and save data use it. */
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

    /** The documented migration: the old preset changes protection and style, nothing else. */
    public CombatSettings toSettings() {
        CombatSettings defaults = CombatSettings.defaults();
        return switch (this) {
            case DEFENSIVE -> defaults.withProtection(ProtectionPolicy.BODYGUARD).withStyle(FightingStyle.BALANCED);
            case GUARD -> defaults.withProtection(ProtectionPolicy.GUARD).withStyle(FightingStyle.BALANCED);
            case PASSIVE -> defaults.withProtection(ProtectionPolicy.AVOID_COMBAT).withStyle(FightingStyle.BALANCED);
        };
    }

    /** The closest legacy label, for old clients, save data and status text. */
    public static CombatMode fromSettings(CombatSettings settings) {
        return switch (settings.protection()) {
            case GUARD -> GUARD;
            case AVOID_COMBAT -> PASSIVE;
            default -> DEFENSIVE;
        };
    }
}
