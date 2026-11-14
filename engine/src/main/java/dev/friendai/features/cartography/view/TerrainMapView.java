package dev.friendai.features.cartography.view;

import dev.friendai.features.cartography.TerrainCell;
import dev.friendai.features.cartography.TerrainGrid;
import dev.friendai.features.cartography.TerrainRoad;
import dev.friendai.features.cartography.TerrainSelection;
import dev.friendai.features.cartography.TerrainSite;
import java.util.ArrayList;
import java.util.List;

/**
 * The bounded map a menu may draw: selected cells of one dimension, plus the named sites and known roads there.
 * Values are already reduced for display — the full grid never crosses the protocol. Unknown ground is absent, not
 * drawn empty, and a stale cell keeps its content but is marked not fresh.
 */
public record TerrainMapView(long generation, List<Cell> cells, List<Site> sites, List<Road> roads) {
    /** At most this many cells travel in one view. */
    public static final int MAX_CELLS = 128;
    /** At most this many sites and roads travel in one view. */
    public static final int MAX_MARKS = 32;

    public static final TerrainMapView EMPTY = new TerrainMapView(0, List.of(), List.of(), List.of());

    public TerrainMapView {
        cells = List.copyOf(cells == null ? List.of() : cells);
        sites = List.copyOf(sites == null ? List.of() : sites);
        roads = List.copyOf(roads == null ? List.of() : roads);
        if (cells.size() > MAX_CELLS) throw new IllegalArgumentException("too many cells");
        if (sites.size() > MAX_MARKS || roads.size() > MAX_MARKS) throw new IllegalArgumentException("too many marks");
    }

    public record Cell(int x, int z, String biome, boolean water, boolean fresh, boolean detailed) {}

    public record Site(int x, int z, String kind, String name) {}

    public record Road(int fromX, int fromZ, int toX, int toZ, String kind) {}

    /** A seeded, spaced selection of one dimension, with its sites and roads, capped for one packet. */
    public static TerrainMapView from(
            TerrainGrid grid, String dimension, long seed, long now, long maxAgeTicks, int maxCells) {
        if (grid == null || dimension == null || dimension.isBlank()) return EMPTY;
        int cap = Math.max(1, Math.min(maxCells, MAX_CELLS));
        List<TerrainCell> scoped = new ArrayList<>();
        for (TerrainCell cell : grid.cells()) {
            if (cell.key().dimension().equals(dimension)) scoped.add(cell);
        }
        List<TerrainCell> selected =
                TerrainSelection.select(scoped, new TerrainSelection.Request(seed, cap, 4, now, maxAgeTicks));
        List<Cell> cells = new ArrayList<>();
        for (TerrainCell cell : selected) {
            if (!cell.key().dimension().equals(dimension)) continue;
            cells.add(new Cell(
                    cell.key().x(),
                    cell.key().z(),
                    cell.biome(),
                    cell.water(),
                    cell.fresh(now, maxAgeTicks),
                    !cell.landmark().isBlank()));
        }
        List<Site> sites = new ArrayList<>();
        for (TerrainSite site : grid.sites()) {
            if (!site.dimension().equals(dimension)) continue;
            sites.add(new Site(site.x(), site.z(), site.kind(), site.name()));
            if (sites.size() >= MAX_MARKS) break;
        }
        List<Road> roads = new ArrayList<>();
        for (TerrainRoad road : grid.roads()) {
            if (!road.dimension().equals(dimension)) continue;
            roads.add(new Road(road.fromX(), road.fromZ(), road.toX(), road.toZ(), road.kind()));
            if (roads.size() >= MAX_MARKS) break;
        }
        return new TerrainMapView(grid.generation(), cells, sites, roads);
    }

    /** A copy with at most the cap; used by the protocol projection. */
    public TerrainMapView truncated(int maxCells) {
        int cap = Math.max(0, Math.min(maxCells, MAX_CELLS));
        return new TerrainMapView(
                generation,
                cells.size() <= cap ? cells : cells.subList(0, cap),
                sites.size() <= MAX_MARKS ? sites : sites.subList(0, MAX_MARKS),
                roads.size() <= MAX_MARKS ? roads : roads.subList(0, MAX_MARKS));
    }
}
