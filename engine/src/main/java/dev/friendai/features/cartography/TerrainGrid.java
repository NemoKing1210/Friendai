package dev.friendai.features.cartography;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A bounded, per-owner grid of observed terrain. It is not a world scan: only {@link #observe} calls add a cell, so an
 * unobserved or unloaded column is unknown and is never treated as empty or passable.
 *
 * <p>Eviction is by insertion order after a cell is refreshed, so the oldest observation leaves first. The grid has
 * no I/O and no Minecraft type; a server adapter supplies observations and the codec persists them.
 */
public final class TerrainGrid {
    public static final int MAX_CELLS = 4096;

    private final int maxCells;
    private final LinkedHashMap<TerrainCell.CellKey, TerrainCell> cells = new LinkedHashMap<>();

    public TerrainGrid() {
        this(MAX_CELLS);
    }

    public TerrainGrid(int maxCells) {
        if (maxCells < 1 || maxCells > MAX_CELLS) throw new IllegalArgumentException("maxCells");
        this.maxCells = maxCells;
    }

    /**
     * Records or refreshes one observed cell. The newest observation moves to the back; the oldest leaves when the
     * cap is reached.
     */
    public void observe(TerrainCell cell) {
        if (cell == null) throw new IllegalArgumentException("cell");
        cells.remove(cell.key());
        cells.put(cell.key(), cell);
        while (cells.size() > maxCells) {
            cells.remove(cells.keySet().iterator().next());
        }
    }

    /** The observed cell, or empty when this column was never observed. */
    public Optional<TerrainCell> cell(TerrainCell.CellKey key) {
        return Optional.ofNullable(cells.get(key));
    }

    /** Whether the column was observed at all; absence is unknown, not empty. */
    public boolean known(TerrainCell.CellKey key) {
        return cells.containsKey(key);
    }

    /** An observed cell only while it is fresh; a stale cell is still known but is not current evidence. */
    public Optional<TerrainCell> freshCell(TerrainCell.CellKey key, long now, long maxAgeTicks) {
        return cell(key).filter(cell -> cell.fresh(now, maxAgeTicks));
    }

    public int size() {
        return cells.size();
    }

    public List<TerrainCell> cells() {
        return List.copyOf(cells.values());
    }

    /** A copy with the same cells and cap, used by the codec and by tests. */
    public TerrainGrid copy() {
        TerrainGrid copy = new TerrainGrid(maxCells);
        cells.values().forEach(copy::observe);
        return copy;
    }

    /** Replaces the content from a decoded record; the caller supplies a fresh grid to keep construction explicit. */
    public void restore(Iterable<TerrainCell> saved) {
        if (saved == null) return;
        for (TerrainCell cell : saved) {
            observe(cell);
        }
    }

    /** The cap this grid enforces. */
    public int maxCells() {
        return maxCells;
    }

    /** An immutable snapshot for a view. */
    public Map<TerrainCell.CellKey, TerrainCell> snapshot() {
        return Map.copyOf(cells);
    }
}
