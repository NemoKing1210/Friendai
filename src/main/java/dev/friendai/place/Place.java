package dev.friendai.place;

import dev.friendai.pathing.Pos;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * One place the companion knows. The name is stored in canonical form so a name and its aliases can be looked
 * up case-insensitively within a dimension; the compact constructor rejects anything the model cannot store
 * and explains the bound in its message.
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
        name = PlaceNames.normalize(name);
        if (name.length() > PlaceLimits.NAME_MAX) {
            throw new IllegalArgumentException("A place name is too long (" + name.length() + " chars, max "
                    + PlaceLimits.NAME_MAX + "). Shorten it.");
        }
        if (kind == null) {
            throw new IllegalArgumentException("A place needs a kind.");
        }
        label = label == null ? "" : label.strip();
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
        aliases = canonicalAliases(aliases);
        if (source == null) {
            throw new IllegalArgumentException("A place needs a source.");
        }
        note = note == null ? "" : note.strip();
        if (note.length() > PlaceLimits.NOTE_MAX) {
            throw new IllegalArgumentException("A place note is too long (" + note.length() + " chars, max "
                    + PlaceLimits.NOTE_MAX + "). Shorten it.");
        }
    }

    private static Set<String> canonicalAliases(Set<String> aliases) {
        if (aliases == null || aliases.isEmpty()) {
            return Set.of();
        }
        if (aliases.size() > PlaceLimits.ALIASES_MAX) {
            throw new IllegalArgumentException(
                    "A place can have at most " + PlaceLimits.ALIASES_MAX + " aliases.");
        }
        Set<String> canonical = new LinkedHashSet<>();
        for (String alias : aliases) {
            String value = PlaceNames.normalize(alias);
            if (value.length() > PlaceLimits.NAME_MAX) {
                throw new IllegalArgumentException("A place alias is too long (" + value.length() + " chars, max "
                        + PlaceLimits.NAME_MAX + "). Shorten it.");
            }
            canonical.add(value);
        }
        return Set.copyOf(canonical);
    }

    private static void checkCoordinate(int value) {
        if (value < -COORDINATE_MAX || value > COORDINATE_MAX) {
            throw new IllegalArgumentException(
                    "A place coordinate must be within ±" + COORDINATE_MAX + ", not " + value + ".");
        }
    }
}
