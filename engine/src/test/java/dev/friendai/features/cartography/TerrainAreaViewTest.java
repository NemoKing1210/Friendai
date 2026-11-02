package dev.friendai.features.cartography;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TerrainAreaViewTest {
    private static TerrainGrid grid() {
        TerrainGrid grid = new TerrainGrid();
        grid.observe(new TerrainCell(
                new TerrainCell.CellKey("minecraft:overworld", 10, 10), "minecraft:plains", 64, false, "", 100));
        grid.observe(new TerrainCell(
                new TerrainCell.CellKey("minecraft:overworld", 11, 10), "minecraft:forest", 65, false, "", 90));
        grid.observe(new TerrainCell(
                new TerrainCell.CellKey("minecraft:overworld", 30, 30), "minecraft:desert", 70, false, "", 100));
        grid.observe(new TerrainCell(
                new TerrainCell.CellKey("minecraft:the_nether", 10, 10),
                "minecraft:nether_wastes",
                50,
                false,
                "",
                100));
        return grid;
    }

    @Test
    void onlyKnownCellsOfTheRightDimensionInsideTheRadiusAreListed() {
        TerrainAreaView view = TerrainAreaView.of(grid(), "minecraft:overworld", 10, 10, 4, 150, 64);
        assertEquals(2, view.cells().size());
        assertTrue(view.cells().stream()
                .allMatch(cell ->
                        cell.biome().startsWith("minecraft:") && !cell.biome().contains("nether")));
        assertTrue(view.cells().stream().noneMatch(cell -> cell.dx() == 20 && cell.dz() == 20));
    }

    @Test
    void nearestCellsComeFirstAndTheFrameIsBounded() {
        TerrainAreaView view = TerrainAreaView.of(grid(), "minecraft:overworld", 10, 10, 64, 150, 1);
        assertEquals(1, view.cells().size());
        assertEquals(0, view.cells().getFirst().dx());
        assertEquals(0, view.cells().getFirst().dz());

        TerrainAreaView all = TerrainAreaView.of(grid(), "minecraft:overworld", 10, 10, 64, 150, 64);
        assertEquals(3, all.cells().size());
        assertEquals(0, all.cells().get(0).dx());
        assertEquals(1, all.cells().get(1).dx());
    }

    @Test
    void ageIsInGameTicksAndAnEmptyAreaIsNotAFullMap() {
        TerrainAreaView view = TerrainAreaView.of(grid(), "minecraft:overworld", 10, 10, 4, 160, 64);
        assertEquals(60, view.cells().getFirst().ageTicks());
        TerrainAreaView empty = TerrainAreaView.of(grid(), "minecraft:overworld", 500, 500, 2, 160, 64);
        assertTrue(empty.empty());
        assertFalse(view.empty());
    }

    @Test
    void invalidArgumentsAreRejected() {
        assertThrows(() -> TerrainAreaView.of(grid(), "minecraft:overworld", 0, 0, 0, 1, 1));
        assertThrows(() -> TerrainAreaView.of(grid(), "minecraft:overworld", 0, 0, 4, 1, 0));
        assertThrows(() -> new TerrainAreaView("", 0, 0, 4, java.util.List.of()));
    }

    private static void assertThrows(Runnable action) {
        try {
            action.run();
            throw new AssertionError("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
