package dev.friendai.features.cartography;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class TerrainGridTest {
    private static TerrainCell cell(String dimension, int x, int z, long seen) {
        return new TerrainCell(new TerrainCell.CellKey(dimension, x, z), "minecraft:plains", 64, false, "", seen);
    }

    @Test
    void anUnobservedColumnIsUnknownNotEmpty() {
        TerrainGrid grid = new TerrainGrid();
        TerrainCell.CellKey key = new TerrainCell.CellKey("minecraft:overworld", 0, 0);

        assertTrue(grid.cell(key).isEmpty());
        assertFalse(grid.known(key), "absence is unknown");
        grid.observe(cell("minecraft:overworld", 0, 0, 10));
        assertTrue(grid.known(key));
        assertEquals(Optional.of(64), grid.cell(key).map(TerrainCell::surfaceY));
    }

    @Test
    void dimensionsAndColumnsAreDistinct() {
        TerrainGrid grid = new TerrainGrid();
        grid.observe(cell("minecraft:overworld", 0, 0, 1));
        grid.observe(cell("minecraft:the_nether", 0, 0, 1));

        assertEquals(2, grid.size());
        assertTrue(grid.known(new TerrainCell.CellKey("minecraft:overworld", 0, 0)));
        assertTrue(grid.known(new TerrainCell.CellKey("minecraft:the_nether", 0, 0)));
        assertFalse(grid.known(new TerrainCell.CellKey("minecraft:overworld", 1, 0)));
    }

    @Test
    void theOldestObservationLeavesWhenTheCapIsReached() {
        TerrainGrid grid = new TerrainGrid(2);
        grid.observe(cell("minecraft:overworld", 0, 0, 1));
        grid.observe(cell("minecraft:overworld", 1, 0, 2));
        grid.observe(cell("minecraft:overworld", 2, 0, 3));

        assertEquals(2, grid.size());
        assertFalse(grid.known(new TerrainCell.CellKey("minecraft:overworld", 0, 0)));
        assertTrue(grid.known(new TerrainCell.CellKey("minecraft:overworld", 2, 0)));
    }

    @Test
    void refreshingMovesACellToTheNewestPosition() {
        TerrainGrid grid = new TerrainGrid(2);
        grid.observe(cell("minecraft:overworld", 0, 0, 1));
        grid.observe(cell("minecraft:overworld", 1, 0, 2));
        grid.observe(cell("minecraft:overworld", 0, 0, 3));
        grid.observe(cell("minecraft:overworld", 2, 0, 4));

        assertEquals(2, grid.size());
        assertFalse(grid.known(new TerrainCell.CellKey("minecraft:overworld", 1, 0)), "the middle cell is oldest");
        assertTrue(grid.known(new TerrainCell.CellKey("minecraft:overworld", 0, 0)));
        assertTrue(grid.known(new TerrainCell.CellKey("minecraft:overworld", 2, 0)));
    }

    @Test
    void sitesAndRoadsAreBoundedAndSurviveACopy() {
        TerrainGrid grid = new TerrainGrid();
        for (int i = 0; i < TerrainGrid.MAX_SITES + 10; i++) {
            grid.addSite(new TerrainSite("minecraft:overworld", i, 0, "village", "v" + i, 1));
        }
        for (int i = 0; i < TerrainGrid.MAX_ROADS + 10; i++) {
            grid.addRoad(new TerrainRoad("minecraft:overworld", i, 0, i, 10, "path", 1));
        }
        grid.setGeneration(9);
        assertEquals(TerrainGrid.MAX_SITES, grid.sites().size());
        assertEquals(TerrainGrid.MAX_ROADS, grid.roads().size());
        TerrainGrid copy = grid.copy();
        assertEquals(9, copy.generation());
        assertEquals(grid.sites(), copy.sites());
        assertEquals(grid.roads(), copy.roads());
    }

    @Test
    void aRoadHasTheSameIdentityFromEitherEnd() {
        TerrainRoad forward = new TerrainRoad("minecraft:overworld", 1, 2, 30, 40, "path", 1);
        TerrainRoad reverse = new TerrainRoad("minecraft:overworld", 30, 40, 1, 2, "path", 2);
        assertEquals(forward.id(), reverse.id());
    }

    @Test
    void freshnessIsAGameTickWindow() {
        TerrainCell c = cell("minecraft:overworld", 0, 0, 100);
        assertTrue(c.fresh(150, 60));
        assertFalse(c.fresh(161, 60));
        assertFalse(c.fresh(99, 60), "a future tick is not fresh evidence");
    }

    @Test
    void theRecordRoundTripsAndRejectsNewerOrCorruptData() {
        TerrainGrid grid = new TerrainGrid();
        grid.observe(new TerrainCell(
                new TerrainCell.CellKey("minecraft:overworld", 4, -7),
                "minecraft:forest",
                70,
                true,
                "minecraft:village",
                1234));

        TerrainGrid loaded = TerrainMapSave.decode(TerrainMapSave.encode(grid)).orElseThrow();
        assertEquals(1, loaded.size());
        TerrainCell restored = loaded.cells().getFirst();
        assertEquals("minecraft:overworld", restored.key().dimension());
        assertEquals(4, restored.key().x());
        assertEquals(-7, restored.key().z());
        assertEquals("minecraft:forest", restored.biome());
        assertEquals(70, restored.surfaceY());
        assertTrue(restored.water());
        assertEquals("minecraft:village", restored.landmark());
        assertEquals(1234, restored.lastSeenTick());

        assertTrue(TerrainMapSave.decode("{\"formatVersion\":3,\"cells\":[]}").isEmpty());
        assertTrue(TerrainMapSave.decode("not json").isEmpty());
        assertTrue(TerrainMapSave.decode("{}").isEmpty());
        assertTrue(TerrainMapSave.decode(null).isEmpty());
        assertTrue(TerrainMapSave.decode("{\"formatVersion\":1,\"cells\":[{\"dimension\":\"\",\"x\":0,\"z\":0}]}")
                .isEmpty());
    }

    @Test
    void invalidCellsAreRejectedBeforeTheyEnterTheGrid() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new TerrainCell(
                        new TerrainCell.CellKey("minecraft:overworld", 0, 0), "minecraft:plains", 400, false, "", 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new TerrainCell(new TerrainCell.CellKey("minecraft:overworld", 0, 0), "", 64, false, "", 0));
        assertThrows(IllegalArgumentException.class, () -> new TerrainCell.CellKey("", 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new TerrainGrid(0));
    }

    private static void assertThrows(Class<? extends RuntimeException> type, Runnable action) {
        try {
            action.run();
            throw new AssertionError("expected " + type.getSimpleName());
        } catch (RuntimeException actual) {
            if (!type.isInstance(actual)) throw actual;
        }
    }
}
