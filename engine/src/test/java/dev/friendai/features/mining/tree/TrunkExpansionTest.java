package dev.friendai.features.mining.tree;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TrunkExpansionTest {
    /** A tiny world: the logs, and the cells a test pretends are not loaded. */
    private record Grid(Set<TreeCell> logs, Set<TreeCell> unloaded) implements TrunkExpansion.Cells {
        @Override
        public boolean isLog(int x, int y, int z) {
            return logs.contains(new TreeCell(x, y, z));
        }

        @Override
        public boolean isLoaded(int x, int y, int z) {
            return !unloaded.contains(new TreeCell(x, y, z));
        }
    }

    private static List<TreeCell> column(int x, int z, int baseY, int height) {
        List<TreeCell> cells = new ArrayList<>();
        for (int i = 0; i < height; i++) {
            cells.add(new TreeCell(x, baseY + i, z));
        }
        return cells;
    }

    private static List<TreeCell> square(int x, int z, int baseY, int layers) {
        List<TreeCell> cells = new ArrayList<>();
        for (int layer = 0; layer < layers; layer++) {
            for (int dx = 0; dx <= 1; dx++) {
                for (int dz = 0; dz <= 1; dz++) {
                    cells.add(new TreeCell(x + dx, baseY + layer, z + dz));
                }
            }
        }
        return cells;
    }

    @Test
    void crownSeedGrowsDownToTheFoot() {
        List<TreeCell> trunk = column(0, 0, 2, 24);
        Grid grid = new Grid(new LinkedHashSet<>(trunk), Set.of());
        List<TreeCell> grown = TrunkExpansion.expand(trunk.subList(21, 24), grid, 512);
        assertEquals(new LinkedHashSet<>(trunk), new LinkedHashSet<>(grown), "every log of the column");
        assertTrue(grown.contains(trunk.get(0)), "the foot is reached");
    }

    @Test
    void truncatedStumpSeedGrowsEveryColumn() {
        List<TreeCell> tree = square(0, 0, 2, 12);
        Grid grid = new Grid(new LinkedHashSet<>(tree), Set.of());
        List<TreeCell> seed = tree.subList(tree.size() - 4, tree.size());
        List<TreeCell> grown = TrunkExpansion.expand(seed, grid, 512);
        assertEquals(48, new LinkedHashSet<>(grown).size(), "all four columns of the 2x2 trunk");
    }

    @Test
    void expansionStopsAtTheFirstNonLog() {
        List<TreeCell> lower = column(0, 0, 2, 4);
        List<TreeCell> upper = column(0, 0, 7, 3); // a gap at y = 6
        Set<TreeCell> logs = new LinkedHashSet<>(lower);
        logs.addAll(upper);
        List<TreeCell> grown = TrunkExpansion.expand(List.of(upper.get(2)), new Grid(logs, Set.of()), 512);
        assertEquals(3, grown.size(), "only the connected branch above the gap: " + grown);
        assertFalse(grown.contains(new TreeCell(0, 6, 0)), "the gap cell is not a log");
    }

    @Test
    void unloadedCellBlocksTheFill() {
        List<TreeCell> trunk = column(0, 0, 2, 10);
        Grid grid = new Grid(new LinkedHashSet<>(trunk), Set.of(new TreeCell(0, 6, 0)));
        List<TreeCell> grown = TrunkExpansion.expand(List.of(trunk.get(9)), grid, 512);
        assertEquals(5, grown.size(), "stops above the unloaded cell: " + grown);
    }

    @Test
    void capTruncatesButKeepsTheSeed() {
        List<TreeCell> trunk = column(0, 0, 2, 24);
        Grid grid = new Grid(new LinkedHashSet<>(trunk), Set.of());
        List<TreeCell> grown = TrunkExpansion.expand(List.of(trunk.get(23)), grid, 5);
        assertEquals(5, grown.size(), "bounded by the cap");
        assertTrue(grown.contains(trunk.get(23)), "the seed is always part of the result");
    }

    @Test
    void sameWorldExpandsTheSameWay() {
        List<TreeCell> tree = square(3, 5, 2, 8);
        Grid grid = new Grid(new LinkedHashSet<>(tree), Set.of());
        assertEquals(
                TrunkExpansion.expand(List.of(tree.get(30)), grid, 512),
                TrunkExpansion.expand(List.of(tree.get(30)), grid, 512));
    }
}
