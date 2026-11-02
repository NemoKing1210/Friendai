package dev.friendai.features.cartography;

import java.util.Locale;

/**
 * A named place the companion actually found — a village, a portal, a trailhead — kept beside the terrain cells so a
 * map view can label it without scanning the world again. Position is a column, not a block: a site is one map mark.
 */
public record TerrainSite(String dimension, int x, int z, String kind, String name, long discoveredTick) {
    /** Longest accepted dimension, kind or name. */
    public static final int MAX_TEXT = 64;

    public TerrainSite {
        dimension = text(dimension, "dimension");
        kind = text(kind, "kind");
        name = text(name, "name");
        if (discoveredTick < 0) throw new IllegalArgumentException("discoveredTick");
    }

    /** Stable identity: one site per dimension, column and kind, even when its name is corrected later. */
    public String id() {
        return dimension + "|" + x + "|" + z + "|" + kind;
    }

    private static String text(String raw, String name) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException(name);
        String value = raw.strip().toLowerCase(Locale.ROOT);
        if (value.length() > MAX_TEXT) throw new IllegalArgumentException(name + " is too long");
        return value;
    }
}
