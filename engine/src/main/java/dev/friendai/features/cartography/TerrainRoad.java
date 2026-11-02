package dev.friendai.features.cartography;

import java.util.Locale;

/**
 * A known route between two columns in one dimension: a walked path, a rail or a bridge. The map draws it as one
 * segment; the companion remembers that the two ends are connected, not every block along the way.
 */
public record TerrainRoad(String dimension, int fromX, int fromZ, int toX, int toZ, String kind, long lastSeenTick) {
    /** Longest accepted dimension or kind. */
    public static final int MAX_TEXT = 64;

    public TerrainRoad {
        dimension = text(dimension, "dimension");
        kind = text(kind, "kind");
        if (fromX == toX && fromZ == toZ) throw new IllegalArgumentException("a road needs two ends");
        if (lastSeenTick < 0) throw new IllegalArgumentException("lastSeenTick");
    }

    /** Stable identity, independent of which end was seen first. */
    public String id() {
        boolean forward = fromX < toX || (fromX == toX && fromZ <= toZ);
        int ax = forward ? fromX : toX;
        int az = forward ? fromZ : toZ;
        int bx = forward ? toX : fromX;
        int bz = forward ? toZ : fromZ;
        return dimension + "|" + kind + "|" + ax + "|" + az + "|" + bx + "|" + bz;
    }

    private static String text(String raw, String name) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException(name);
        String value = raw.strip().toLowerCase(Locale.ROOT);
        if (value.length() > MAX_TEXT) throw new IllegalArgumentException(name + " is too long");
        return value;
    }
}
