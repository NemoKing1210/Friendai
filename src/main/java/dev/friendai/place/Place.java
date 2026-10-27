package dev.friendai.place;

import dev.friendai.pathing.Pos;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * One place the companion knows. Names and aliases are stored as the owner typed them (trimmed); the
 * case-insensitive, per-dimension lookup uses {@link PlaceNames#key(String, String)}. The compact constructor
 * rejects anything the model cannot store and explains the bound in its message.
 */
public record Place(
        long id,
        String name,
        PlaceKind kind,
        String label,
        String dimension,
        Pos anchor,
        int radius,
        Set<String> aliases,
        PlaceSource source,
        UUID author,
        long createdTick,
        long updatedTick,
        long lastVisitTick,
        int visits,
        String note) {

    /** The largest box half-size a place may cover. */
    public static final int RADIUS_MAX = 64;

    /** Every coordinate stays within the world border limits. */
    public static final int COORDINATE_MAX = 30_000_000;

    public Place {
        if (id < 1) {
            throw new IllegalArgumentException("A place needs a positive id.");
        }
        PlaceNames.normalize(name); // rejects blank names and control characters; the lookup form
        name = name.strip();
        if (name.length() > PlaceLimits.NAME_MAX) {
            throw new IllegalArgumentException("A place name is too long (" + name.length() + " chars, max "
                    + PlaceLimits.NAME_MAX + "). Shorten it.");
        }
        if (kind == null) {
            throw new IllegalArgumentException("A place needs a kind.");
        }
        label = checkedText(label, "label").strip();
        if (label.length() > PlaceLimits.LABEL_MAX) {
            throw new IllegalArgumentException("A place label is too long (" + label.length() + " chars, max "
                    + PlaceLimits.LABEL_MAX + "). Shorten it.");
        }
        if (dimension == null || dimension.isBlank()) {
            throw new IllegalArgumentException("A place needs a dimension.");
        }
        dimension = dimension.strip();
        if (anchor == null) {
            throw new IllegalArgumentException("A place needs an anchor position.");
        }
        checkCoordinate(anchor.x());
        checkCoordinate(anchor.y());
        checkCoordinate(anchor.z());
        if (radius < 1 || radius > RADIUS_MAX) {
            throw new IllegalArgumentException(
                    "A place radius must be 1-" + RADIUS_MAX + ", not " + radius + ".");
        }
        aliases = typedAliases(aliases);
        if (source == null) {
            throw new IllegalArgumentException("A place needs a source.");
        }
        note = checkedText(note, "note").strip();
        if (note.length() > PlaceLimits.NOTE_MAX) {
            throw new IllegalArgumentException("A place note is too long (" + note.length() + " chars, max "
                    + PlaceLimits.NOTE_MAX + "). Shorten it.");
        }
    }

    private static Set<String> typedAliases(Set<String> aliases) {
        if (aliases == null || aliases.isEmpty()) {
            return Set.of();
        }
        if (aliases.size() > PlaceLimits.ALIASES_MAX) {
            throw new IllegalArgumentException(
                    "A place can have at most " + PlaceLimits.ALIASES_MAX + " aliases.");
        }
        Map<String, String> byKey = new LinkedHashMap<>();
        for (String alias : aliases) {
            String normalized = PlaceNames.normalize(alias);
            String typed = alias.strip();
            if (typed.length() > PlaceLimits.NAME_MAX) {
                throw new IllegalArgumentException("A place alias is too long (" + typed.length() + " chars, max "
                        + PlaceLimits.NAME_MAX + "). Shorten it.");
            }
            byKey.putIfAbsent(normalized, typed);
        }
        return Set.copyOf(byKey.values());
    }

    /** Text that reaches the prompt must stay one line; control characters would let it forge new lines. */
    private static String checkedText(String text, String what) {
        if (text == null) {
            return "";
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int type = Character.getType(c);
            if (Character.isISOControl(c) || type == Character.LINE_SEPARATOR
                    || type == Character.PARAGRAPH_SEPARATOR) {
                throw new IllegalArgumentException("A place " + what + " cannot contain control characters.");
            }
        }
        return text;
    }

    private static void checkCoordinate(int value) {
        if (value < -COORDINATE_MAX || value > COORDINATE_MAX) {
            throw new IllegalArgumentException(
                    "A place coordinate must be within ±" + COORDINATE_MAX + ", not " + value + ".");
        }
    }
}
