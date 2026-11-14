package dev.friendai.features.cartography;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Deterministic cell selection for a map view. Observation order is not a priority: a seeded hash decides the tie
 * between equally fresh cells, so two clients with the same grid and seed draw the same view. Freshness and a
 * detailed observation rank first; spacing thins dense areas so a single explored cave does not fill the whole map.
 * Unobserved columns are never selected: absence stays unknown, not empty.
 */
public final class TerrainSelection {
    /** At most this many cells are returned, whatever the request asks for. */
    public static final int MAX_VIEW_CELLS = 2048;

    /** What a view asks for: a seed, a cap, the minimum block distance between cells and the freshness horizon. */
    public record Request(long seed, int maxCells, int minSpacing, long now, long maxAgeTicks) {
        public Request {
            if (maxCells < 1 || maxCells > MAX_VIEW_CELLS) throw new IllegalArgumentException("maxCells");
            if (minSpacing < 0) throw new IllegalArgumentException("minSpacing");
            if (maxAgeTicks < 0) throw new IllegalArgumentException("maxAgeTicks");
        }
    }

    private TerrainSelection() {}

    public static List<TerrainCell> select(TerrainGrid grid, Request request) {
        if (grid == null || request == null) return List.of();
        return select(grid.cells(), request);
    }

    /**
     * The same selection over an already filtered view, so a caller that wants one dimension does not have to copy
     * the whole grid first.
     */
    public static List<TerrainCell> select(Iterable<TerrainCell> cells, Request request) {
        if (cells == null || request == null) return List.of();
        List<TerrainCell> candidates = new ArrayList<>();
        for (TerrainCell cell : cells) candidates.add(cell);
        candidates.sort(Comparator.comparingInt((TerrainCell cell) -> rank(cell, request))
                .thenComparingLong(cell -> mix(request.seed(), cell.key())));
        List<TerrainCell> chosen = new ArrayList<>();
        for (TerrainCell cell : candidates) {
            if (chosen.size() >= request.maxCells()) break;
            boolean crowded = false;
            for (TerrainCell other : chosen) {
                if (other.key().dimension().equals(cell.key().dimension())
                        && other.key().x() == cell.key().x()
                        && other.key().z() == cell.key().z()) {
                    crowded = true;
                    break;
                }
                if (other.key().dimension().equals(cell.key().dimension())
                        && squaredDistance(other, cell) < (long) request.minSpacing() * request.minSpacing()) {
                    crowded = true;
                    break;
                }
            }
            if (!crowded) chosen.add(cell);
        }
        return List.copyOf(chosen);
    }

    /** 0 for a fresh, detailed cell, 1 for a fresh sampled cell, 2 for a stale, detailed one, 3 otherwise. */
    private static int rank(TerrainCell cell, Request request) {
        boolean fresh = cell.fresh(request.now(), request.maxAgeTicks());
        if (fresh) return !cell.landmark().isBlank() ? 0 : 1;
        return !cell.landmark().isBlank() ? 2 : 3;
    }

    private static long squaredDistance(TerrainCell a, TerrainCell b) {
        long dx = (long) a.key().x() - b.key().x();
        long dz = (long) a.key().z() - b.key().z();
        return dx * dx + dz * dz;
    }

    private static long mix(long seed, TerrainCell.CellKey key) {
        long h = seed ^ key.dimension().hashCode();
        h = h * 0x9E3779B97F4A7C15L + key.x();
        h = h * 0x9E3779B97F4A7C15L + key.z();
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        return h;
    }
}
