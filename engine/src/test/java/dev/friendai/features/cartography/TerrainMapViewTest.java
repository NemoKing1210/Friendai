package dev.friendai.features.cartography;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.features.cartography.view.TerrainMapView;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TerrainMapViewTest {
    private static final String OVERWORLD = "minecraft:overworld";

    @Test
    void oneDimensionIsSelectedAndCapped() {
        TerrainGrid grid = new TerrainGrid();
        for (int x = 0; x < 200; x++) {
            grid.observe(
                    new TerrainCell(new TerrainCell.CellKey(OVERWORLD, x, 0), "minecraft:plains", 64, false, "", 10));
            grid.observe(new TerrainCell(
                    new TerrainCell.CellKey("minecraft:the_nether", x, 0),
                    "minecraft:nether_wastes",
                    50,
                    false,
                    "",
                    10));
        }
        TerrainMapView view = TerrainMapView.from(grid, OVERWORLD, 1L, 20, 100, 40);
        assertEquals(40, view.cells().size());
        assertTrue(view.cells().stream().allMatch(cell -> cell.biome().equals("minecraft:plains")));
        for (int i = 0; i < view.cells().size(); i++)
            for (int j = i + 1; j < view.cells().size(); j++) {
                int dx = view.cells().get(i).x() - view.cells().get(j).x();
                int dz = view.cells().get(i).z() - view.cells().get(j).z();
                assertTrue(dx * dx + dz * dz >= 16, "cells are spaced for a readable map: " + view.cells());
            }
    }

    @Test
    void sitesAndRoadsAreDimensionScopedAndBounded() {
        TerrainGrid grid = new TerrainGrid();
        for (int i = 0; i < 50; i++) {
            grid.addSite(new TerrainSite(OVERWORLD, i, 0, "village", "v" + i, 1));
            grid.addSite(new TerrainSite("minecraft:the_nether", i, 0, "fortress", "f" + i, 1));
            grid.addRoad(new TerrainRoad(OVERWORLD, i, 0, i, 8, "path", 1));
        }
        TerrainMapView view = TerrainMapView.from(grid, OVERWORLD, 2L, 20, 100, 8);
        assertEquals(TerrainMapView.MAX_MARKS, view.sites().size());
        assertEquals(TerrainMapView.MAX_MARKS, view.roads().size());
        assertTrue(view.sites().stream().allMatch(site -> site.name().startsWith("v")));
    }

    @Test
    void emptyAndUnknownMapsStayEmpty() {
        assertEquals(TerrainMapView.EMPTY, TerrainMapView.from(null, OVERWORLD, 0, 0, 0, 8));
        assertEquals(TerrainMapView.EMPTY, TerrainMapView.from(new TerrainGrid(), OVERWORLD, 0, 0, 0, 8));
        assertTrue(TerrainMapView.from(new TerrainGrid(), "minecraft:the_end", 0, 0, 0, 8)
                .cells()
                .isEmpty());
    }

    @Test
    void freshnessAndLandmarksAreExplicit() {
        TerrainGrid grid = new TerrainGrid();
        grid.observe(
                new TerrainCell(new TerrainCell.CellKey(OVERWORLD, 0, 0), "minecraft:plains", 64, true, "village", 10));
        TerrainMapView view = TerrainMapView.from(grid, OVERWORLD, 0, 500, 100, 8);
        assertEquals(1, view.cells().size());
        assertEquals(false, view.cells().getFirst().fresh());
        assertEquals(true, view.cells().getFirst().water());
        assertEquals(true, view.cells().getFirst().detailed());
        assertEquals(Set.of(OVERWORLD), Set.of(OVERWORLD));
    }
}
