package dev.friendai.features.cartography;

import java.util.Locale;

/**
 * One sampled terrain cell in a dimension. Only what the companion actually observed is stored: a biome id, the
 * surface height, whether the surface is water, and an optional landmark reference. A cell that was never observed
 * simply has no entry — absence is unknown, never "empty".
 *
 * <p>{@code lastSeenTick} is a game tick, so freshness is comparable with the world clock and survives a restart
 * without wall time. Every text field is bounded here, so a corrupt or oversized record cannot grow the grid.
 */
public record TerrainCell(CellKey key, String biome, int surfaceY, boolean water, String landmark, long lastSeenTick) {
    /** Longest accepted biome or landmark id. */
    public static final int MAX_TEXT = 64;
    /** Minecraft's build height bounds, so a corrupt Y cannot claim an impossible surface. */
    public static final int MIN_Y = -64;

    public static final int MAX_Y = 320;

    public TerrainCell {
        if (key == null) throw new IllegalArgumentException("key");
        biome = text(biome, "biome");
        landmark = landmark == null || landmark.isBlank() ? "" : text(landmark, "landmark");
        if (surfaceY < MIN_Y || surfaceY > MAX_Y) throw new IllegalArgumentException("surfaceY");
        if (lastSeenTick < 0) throw new IllegalArgumentException("lastSeenTick");
    }

    /** Whether the terrain was observed no longer than {@code maxAgeTicks} before {@code now}. */
    public boolean fresh(long now, long maxAgeTicks) {
        return lastSeenTick <= now && now - lastSeenTick <= Math.max(0, maxAgeTicks);
    }

    private static String text(String raw, String name) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException(name);
        String value = raw.strip().toLowerCase(Locale.ROOT);
        if (value.length() > MAX_TEXT) throw new IllegalArgumentException(name + " is too long");
        return value;
    }

    /** A stable, dimension-scoped column identity. */
    public record CellKey(String dimension, int x, int z) {
        public CellKey {
            if (dimension == null || dimension.isBlank()) throw new IllegalArgumentException("dimension");
            dimension = dimension.strip().toLowerCase(Locale.ROOT);
            if (dimension.length() > MAX_TEXT) throw new IllegalArgumentException("dimension is too long");
        }
    }
}
