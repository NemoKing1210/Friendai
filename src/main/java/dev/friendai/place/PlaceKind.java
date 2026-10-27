package dev.friendai.place;

import java.util.Locale;

/**
 * What a named place is for. The enum name is what the file stores; {@link #parse(String)} also understands the
 * words players and plans use for the same idea.
 */
public enum PlaceKind {
    HOUSE,
    FARM,
    MINE,
    STORAGE,
    PORTAL,
    TRADE,
    LANDMARK;

    /**
     * Reads a kind from the enum name or a synonym; case-insensitive. Throws
     * {@link IllegalArgumentException} with a message written for the model.
     */
    public static PlaceKind parse(String raw) {
        if (raw != null && !raw.isBlank()) {
            String text = raw.strip().toLowerCase(Locale.ROOT);
            switch (text) {
                case "house", "home", "base":
                    return HOUSE;
                case "farm":
                    return FARM;
                case "mine":
                    return MINE;
                case "storage", "chest":
                    return STORAGE;
                case "portal":
                    return PORTAL;
                case "trade", "village":
                    return TRADE;
                case "landmark":
                    return LANDMARK;
                default:
                    break;
            }
        }
        throw new IllegalArgumentException("unknown place kind; use " + list());
    }

    /**
     * Guesses a kind from the words of a name a task supplies, so a {@code wheat_farm} blueprint is not recorded
     * as a house. Anything unrecognised is a house.
     */
    public static PlaceKind inferFromName(String text) {
        if (text == null || text.isBlank()) {
            return HOUSE;
        }
        String[] words = text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+");
        if (hasAny(words, "farm", "wheat", "iron")) {
            return FARM;
        }
        if (hasAny(words, "mine", "shaft", "quarry")) {
            return MINE;
        }
        if (hasAny(words, "portal")) {
            return PORTAL;
        }
        if (hasAny(words, "storage", "chest", "shed")) {
            return STORAGE;
        }
        if (hasAny(words, "village", "trading")) {
            return TRADE;
        }
        return HOUSE;
    }

    private static boolean hasAny(String[] words, String... candidates) {
        for (String word : words) {
            for (String candidate : candidates) {
                if (word.equals(candidate)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String list() {
        StringBuilder out = new StringBuilder();
        for (PlaceKind kind : values()) {
            if (!out.isEmpty()) {
                out.append(", ");
            }
            out.append(kind.name().toLowerCase(Locale.ROOT));
        }
        return out.toString();
    }
}
