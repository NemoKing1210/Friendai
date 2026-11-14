package dev.friendai.features.cartography;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class TerrainSelectionTest {
    private static final String OVERWORLD = "minecraft:overworld";

    @Test
    void theSameSeedProducesTheSameView() {
        TerrainGrid grid = new TerrainGrid();
        for (int i = 0; i < 40; i++) {
            grid.observe(cell(i, i));
        }
        var request = new TerrainSelection.Request(7L, 10, 0, 100, 1000);
        assertEquals(TerrainSelection.select(grid, request), TerrainSelection.select(grid, request));
    }

    @Test
    void theIterableViewSelectsTheSameCellsAsTheGrid() {
        TerrainGrid grid = new TerrainGrid();
        for (int i = 0; i < 40; i++) {
            grid.observe(cell(i, i));
        }
        var request = new TerrainSelection.Request(7L, 10, 4, 100, 1000);

        assertEquals(TerrainSelection.select(grid, request), TerrainSelection.select(grid.cells(), request));
    }

    @Test
    void aDifferentSeedProducesADifferentOrder() {
        TerrainGrid grid = new TerrainGrid();
        for (int i = 0; i < 40; i++) {
            grid.observe(cell(i, 0));
        }
        List<TerrainCell> a = TerrainSelection.select(grid, new TerrainSelection.Request(1L, 40, 0, 100, 1000));
        List<TerrainCell> b = TerrainSelection.select(grid, new TerrainSelection.Request(2L, 40, 0, 100, 1000));
        assertFalse(a.equals(b), "different seeds order equally fresh cells differently");
    }

    @Test
    void spacingThinsDenseAreasAndTheCapHolds() {
        TerrainGrid grid = new TerrainGrid();
        for (int x = 0; x < 20; x++) {
            for (int z = 0; z < 20; z++) {
                grid.observe(cell(x, z));
            }
        }
        List<TerrainCell> selected = TerrainSelection.select(grid, new TerrainSelection.Request(3L, 4, 8, 100, 1000));
        assertEquals(4, selected.size());
        for (int i = 0; i < selected.size(); i++) {
            for (int j = i + 1; j < selected.size(); j++) {
                TerrainCell a = selected.get(i);
                TerrainCell b = selected.get(j);
                int dx = a.key().x() - b.key().x();
                int dz = a.key().z() - b.key().z();
                assertTrue(dx * dx + dz * dz >= 64, "cells stay at least eight blocks apart");
            }
        }
    }

    @Test
    void aFreshDetailedCellOutranksAStaleSample() {
        TerrainGrid grid = new TerrainGrid();
        grid.observe(new TerrainCell(new TerrainCell.CellKey(OVERWORLD, 0, 0), "minecraft:plains", 64, false, "", 1));
        grid.observe(new TerrainCell(
                new TerrainCell.CellKey(OVERWORLD, 40, 0), "minecraft:plains", 64, true, "village", 500));
        List<TerrainCell> selected = TerrainSelection.select(grid, new TerrainSelection.Request(0L, 1, 0, 600, 200));
        assertEquals(40, selected.getFirst().key().x(), "the fresh detailed cell is chosen first");
    }

    private static TerrainCell cell(int x, int z) {
        return new TerrainCell(new TerrainCell.CellKey(OVERWORLD, x, z), "minecraft:plains", 64, true, "", 10);
    }
}
