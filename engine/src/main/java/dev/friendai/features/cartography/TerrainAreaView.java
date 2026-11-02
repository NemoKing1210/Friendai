package dev.friendai.features.cartography;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A compact view of the explored terrain around one point, for a menu or a prompt block. It never sends the owner's
 * full map: only known cells inside the radius, at most {@link #MAX_CELLS}, ordered by distance so a frame cut keeps
 * the nearest ground. A cell's age is in game ticks; stale cells stay listed with their age rather than being
 * presented as current.
 */
public record TerrainAreaView(String dimension, int centerX, int centerZ, int radius, List<Cell> cells) {
    public static final int MAX_CELLS = 64;
    public static final int MAX_RADIUS = 64;

    public record Cell(int dx, int dz, String biome, int surfaceY, boolean water, long ageTicks) {
        public Cell {
            biome = biome == null ? "" : biome;
            ageTicks = Math.max(0, ageTicks);
        }
    }

    public TerrainAreaView {
        if (dimension == null || dimension.isBlank()) throw new IllegalArgumentException("dimension");
        if (radius < 1 || radius > MAX_RADIUS) throw new IllegalArgumentException("radius");
        cells = List.copyOf(cells == null ? List.of() : cells);
        if (cells.size() > MAX_CELLS) throw new IllegalArgumentException("view exceeds " + MAX_CELLS + " cells");
    }

    /** The nearest known cells in one dimension, bounded and deterministic. */
    public static TerrainAreaView of(
            TerrainGrid grid, String dimension, int centerX, int centerZ, int radius, long now, int maxCells) {
        if (grid == null) throw new IllegalArgumentException("grid");
        if (radius < 1 || radius > MAX_RADIUS) throw new IllegalArgumentException("radius");
        if (maxCells < 1 || maxCells > MAX_CELLS) throw new IllegalArgumentException("maxCells");
        List<Scored> found = new ArrayList<>();
        for (TerrainCell cell : grid.cells()) {
            if (!cell.key().dimension().equals(dimension)) {
                continue;
            }
            int dx = cell.key().x() - centerX;
            int dz = cell.key().z() - centerZ;
            if (Math.abs(dx) > radius || Math.abs(dz) > radius) {
                continue;
            }
            found.add(new Scored(
                    new Cell(
                            dx,
                            dz,
                            cell.biome(),
                            cell.surfaceY(),
                            cell.water(),
                            Math.max(0, now - cell.lastSeenTick())),
                    dx * dx + dz * dz));
        }
        found.sort(Comparator.comparingInt(Scored::distance)
                .thenComparingInt(scored -> scored.cell().dx())
                .thenComparingInt(scored -> scored.cell().dz()));
        List<Cell> selected = new ArrayList<>(Math.min(maxCells, found.size()));
        for (Scored scored : found) {
            if (selected.size() >= maxCells) break;
            selected.add(scored.cell());
        }
        return new TerrainAreaView(dimension, centerX, centerZ, radius, selected);
    }

    public boolean empty() {
        return cells.isEmpty();
    }

    private record Scored(Cell cell, int distance) {}
}
