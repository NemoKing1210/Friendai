package dev.friendai.features.brewing;

import java.util.Locale;

/**
 * One vanilla brewing operation: a bottle of {@code fromPotion} plus {@code ingredient} becomes
 * {@code toPotion}. Item and potion ids are data, resolved against the registry by the adapter; the pure planner
 * never hard-codes a Java constant for them.
 */
public record BrewStep(String fromPotion, String ingredient, String toPotion) {
    /** Longest accepted id, so a malformed catalog entry cannot grow the record without bound. */
    public static final int MAX_ID = 120;

    public BrewStep {
        fromPotion = id(fromPotion, "fromPotion");
        ingredient = id(ingredient, "ingredient");
        toPotion = id(toPotion, "toPotion");
    }

    private static String id(String raw, String name) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        String value = raw.strip().toLowerCase(Locale.ROOT);
        if (value.length() > MAX_ID) {
            throw new IllegalArgumentException(name + " is too long");
        }
        return value;
    }
}
