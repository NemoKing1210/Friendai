package dev.friendai.features.places;

import java.util.Locale;
import java.util.Objects;

/**
 * Chooses the name an automatically remembered place gets, so a farm found twice is still "wheat farm" and a
 * second one becomes "wheat farm 2". Names are unique per dimension (name and alias alike), bounded by
 * {@link PlaceLimits#NAME_MAX}, and truncated without splitting a surrogate pair.
 */
public final class PlaceNamer {

    private static final int LAST_SUFFIX = 999;

    private PlaceNamer() {}

    /**
     * The first free automatic name for {@code kind} in {@code dimension}. Throws {@link IllegalArgumentException}
     * when all 999 suffixes are taken, which the caller may treat as "skip this place".
     */
    public static String autoName(PlaceRegistry registry, String dimension, PlaceKind kind, String label) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(kind, "kind");
        String dim = requireDimension(dimension);
        String base = base(kind, label);

        String name = fit(base, PlaceLimits.NAME_MAX);
        if (isFree(registry, name, dim)) {
            return name;
        }
        for (int suffix = 2; suffix <= LAST_SUFFIX; suffix++) {
            String tail = " " + suffix;
            String candidate = fit(base, PlaceLimits.NAME_MAX - tail.length()) + tail;
            if (isFree(registry, candidate, dim)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("There is no free " + kind.name().toLowerCase(Locale.ROOT) + " name left in "
                + dim + "; forget a place first.");
    }

    private static String base(PlaceKind kind, String label) {
        String text = safeLabel(label);
        switch (kind) {
            case FARM:
                return text.isEmpty() ? "farm" : text + " farm";
            case HOUSE:
                return "house";
            case MINE:
                return "mine";
            case STORAGE:
                return "storage";
            case PORTAL:
                return "portal";
            case TRADE:
                return "trading post";
            case LANDMARK:
                return "landmark";
            default:
                throw new IllegalArgumentException("There is no automatic name for " + kind + ".");
        }
    }

    /** A label the model can store; labels a place would reject (control characters) name nothing. */
    private static String safeLabel(String label) {
        if (label == null) {
            return "";
        }
        String text = label.strip();
        try {
            PlaceNames.normalize(text);
        } catch (IllegalArgumentException notALabel) {
            return "";
        }
        return text;
    }

    /** Trims {@code text} to at most {@code maxChars} chars without cutting a surrogate pair in half. */
    private static String fit(String text, int maxChars) {
        if (text.length() <= maxChars) {
            return text;
        }
        int end = 0;
        while (end < text.length()) {
            int width = Character.charCount(text.codePointAt(end));
            if (end + width > maxChars) {
                break;
            }
            end += width;
        }
        return text.substring(0, end).stripTrailing();
    }

    private static boolean isFree(PlaceRegistry registry, String name, String dimension) {
        return registry.find(name, dimension).isEmpty();
    }

    private static String requireDimension(String dimension) {
        if (dimension == null || dimension.isBlank()) {
            throw new IllegalArgumentException("A place needs a dimension.");
        }
        return dimension.strip();
    }
}
