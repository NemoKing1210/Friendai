package dev.friendai.features.places;

import dev.friendai.features.navigation.pathing.Pos;
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
        String note,
        int below,
        int above) {

    /** The largest box half-size a place may cover. */
    public static final int RADIUS_MAX = 64;

    /** Every coordinate stays within the world border limits. */
    public static final int COORDINATE_MAX = 30_000_000;

    /** How far below the anchor a place reaches unless it was set otherwise: its floor, not its cellar. */
    public static final int DEFAULT_BELOW = 1;

    /** How far above the anchor a place reaches unless it was set otherwise. */
    public static final int DEFAULT_ABOVE = 8;

    /**
     * The band a place gets when the caller did not name one. The horizontal radius may be large, but a house
     * only reaches a few blocks above its anchor and one below it: the stone under the floor is not the house.
     */
    public static int defaultBelow(int radius) {
        return Math.min(DEFAULT_BELOW, Math.max(0, radius));
    }

    /** @see #defaultBelow(int) */
    public static int defaultAbove(int radius) {
        return Math.min(DEFAULT_ABOVE, Math.max(0, radius));
    }

    /** A place with the default vertical band; use the canonical constructor to set one explicitly. */
    public Place(
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
        this(
                id,
                name,
                kind,
                label,
                dimension,
                anchor,
                radius,
                aliases,
                source,
                author,
                createdTick,
                updatedTick,
                lastVisitTick,
                visits,
                note,
                defaultBelow(radius),
                defaultAbove(radius));
    }

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
            throw new IllegalArgumentException("A place radius must be 1-" + RADIUS_MAX + ", not " + radius + ".");
        }
        if (below < 0 || below > RADIUS_MAX || above < 0 || above > RADIUS_MAX) {
            throw new IllegalArgumentException("A place band must be 0-" + RADIUS_MAX
                    + " blocks below and above the anchor, not " + below + " and " + above + ".");
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
            throw new IllegalArgumentException("A place can have at most " + PlaceLimits.ALIASES_MAX + " aliases.");
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

    /**
     * Whether {@code pos} lies inside this place's footprint: the horizontal square of {@link #radius()} around
     * the anchor, between {@link #below()} and {@link #above()} blocks vertically. A place is a room, not a cube:
     * the rock under its floor and the air over its roof are outside.
     */
    public boolean contains(Pos pos) {
        return pos != null
                && Math.abs(pos.x() - anchor.x()) <= radius
                && Math.abs(pos.z() - anchor.z()) <= radius
                && pos.y() >= anchor.y() - below
                && pos.y() <= anchor.y() + above;
    }

    /** Text that reaches the prompt must stay one line; control characters would let it forge new lines. */
    private static String checkedText(String text, String what) {
        if (text == null) {
            return "";
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int type = Character.getType(c);
            if (Character.isISOControl(c)
                    || type == Character.LINE_SEPARATOR
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
