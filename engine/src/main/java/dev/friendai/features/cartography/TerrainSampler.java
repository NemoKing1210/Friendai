package dev.friendai.features.cartography;

import java.util.Optional;

/**
 * Bounded, loaded-only sampling policy. The caller supplies a source that answers whether a column is loaded and, only
 * for loaded columns, a terrain observation. The sampler never asks for an unloaded column and never exceeds the
 * per-tick cell budget; the cursor survives between calls so a large radius is covered over several ticks instead of
 * in one scan.
 *
 * <p>Pure: no world read, no chunk request, no Minecraft import. A column the source cannot describe is skipped, not
 * stored as empty.
 */
public final class TerrainSampler {
    /** One observation supplied by the adapter for a loaded column. */
    public record Observation(
            String dimension, int x, int z, String biome, int surfaceY, boolean water, String landmark) {}

    /** The adapter's world view. {@code observe} must be called only after {@code loaded} returned true. */
    public interface Source {
        boolean loaded(int x, int z);

        Optional<Observation> observe(int x, int z);
    }

    /** The next relative column to visit; {@code x,z} are offsets from the centre. */
    public record Cursor(int x, int z) {
        public Cursor {
            if (x < -512 || x > 512 || z < -512 || z > 512) throw new IllegalArgumentException("cursor");
        }

        public static Cursor start(int radius) {
            return new Cursor(-Math.max(1, radius), -Math.max(1, radius));
        }
    }

    /** What one tick did: the updated grid, the next cursor, and observable counters. */
    public record Result(TerrainGrid grid, Cursor next, int sampled, int skipped) {
        public Result {
            if (sampled < 0 || skipped < 0) throw new IllegalArgumentException("counters");
        }
    }

    private TerrainSampler() {}

    /**
     * Visits at most {@code maxCells} columns, starting at {@code cursor}, and records every loaded observation. A
     * skipped column (unloaded or without an observation) still advances the cursor, so the scan is fair and bounded.
     */
    public static Result sample(
            TerrainGrid grid,
            Source source,
            Cursor cursor,
            int centerX,
            int centerZ,
            int radius,
            int maxCells,
            long tick) {
        if (grid == null || source == null || cursor == null) throw new IllegalArgumentException("null argument");
        if (radius < 1 || radius > 512) throw new IllegalArgumentException("radius");
        if (maxCells < 1) throw new IllegalArgumentException("maxCells");
        if (tick < 0) throw new IllegalArgumentException("tick");
        int width = radius * 2 + 1;
        int cells = width * width;
        int x = cursor.x();
        int z = cursor.z();
        int sampled = 0;
        int skipped = 0;
        int visited = 0;
        while (visited < cells && sampled < maxCells) {
            int worldX = centerX + x;
            int worldZ = centerZ + z;
            if (!source.loaded(worldX, worldZ)) {
                skipped++;
            } else {
                Optional<Observation> observation = source.observe(worldX, worldZ);
                if (observation.isPresent()) {
                    Observation value = observation.get();
                    grid.observe(new TerrainCell(
                            new TerrainCell.CellKey(value.dimension(), value.x(), value.z()),
                            value.biome(),
                            value.surfaceY(),
                            value.water(),
                            value.landmark(),
                            tick));
                    sampled++;
                } else {
                    skipped++;
                }
            }
            visited++;
            x++;
            if (x > radius) {
                x = -radius;
                z++;
                if (z > radius) {
                    z = -radius;
                }
            }
        }
        return new Result(grid, new Cursor(x, z), sampled, skipped);
    }
}
