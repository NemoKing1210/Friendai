package dev.friendai.place;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** Normalization and display helpers shared by the model, the file and the prompts. */
public final class PlaceNames {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final String SEEDS = "_seeds";
    /** The prefix a tool's {@code near} argument uses to mean "around this remembered place". */
    private static final String NEAR_PREFIX = "place:";

    private PlaceNames() {}

    /**
     * The place name in a {@code near} value such as {@code place:home}, or empty when the value does not use
     * that form. The prefix is case-insensitive and may be followed by any known name or alias; a bare
     * {@code place:} yields an empty name, so the lookup rejects it with the usual name message.
     */
    public static Optional<String> fromNear(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String text = raw.strip();
        if (!text.toLowerCase(Locale.ROOT).startsWith(NEAR_PREFIX)) {
            return Optional.empty();
        }
        return Optional.of(text.substring(NEAR_PREFIX.length()).strip());
    }

    /**
     * Canonical form of a place name: trimmed, inner whitespace collapsed to one space and lowercased, so
     * {@code "  Пшеничная\tФЕРМА "} and {@code "пшеничная ферма"} are the same name. Throws
     * {@link IllegalArgumentException} for blank names and for control characters: a tab only separates words,
     * but a line break would let a name smuggle new prompt lines. Any script is kept as it is.
     */
    public static String normalize(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("A place name cannot be empty.");
        }
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c != '\t' && Character.isISOControl(c)) {
                throw new IllegalArgumentException("A place name cannot contain control characters.");
            }
        }
        String collapsed = WHITESPACE.matcher(raw.strip()).replaceAll(" ").toLowerCase(Locale.ROOT);
        if (collapsed.isBlank()) {
            throw new IllegalArgumentException("A place name cannot be empty.");
        }
        return collapsed;
    }

    /** The lookup key of a name or alias within one dimension; names are unique per dimension only. */
    public static String key(String name, String dimension) {
        if (dimension == null || dimension.isBlank()) {
            throw new IllegalArgumentException("A place needs a dimension.");
        }
        return normalize(name) + '\u0000' + dimension.strip();
    }

    /** {@code "small_house"} to {@code "small house"}; the label form a player reads. */
    public static String humanize(String id) {
        if (id == null) {
            return "";
        }
        return WHITESPACE
                .matcher(id.strip().replace('_', ' ').replace('-', ' '))
                .replaceAll(" ")
                .toLowerCase(Locale.ROOT)
                .strip();
    }

    /** {@code "minecraft:wheat_seeds"} to {@code "wheat"}; the crop name a farm place uses as its label. */
    public static String shortItem(String itemPath) {
        if (itemPath == null) {
            return "";
        }
        String path = itemPath.strip();
        int colon = path.indexOf(':');
        if (colon >= 0) {
            path = path.substring(colon + 1);
        }
        if (path.endsWith(SEEDS)) {
            path = path.substring(0, path.length() - SEEDS.length());
        }
        return path;
    }
}
