package dev.friendai.features.cartography;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TerrainSamplerTest {
    private static final class Source implements TerrainSampler.Source {
        final Set<Long> loaded = new HashSet<>();
        final List<Long> observed = new ArrayList<>();
        boolean describe = true;

        void load(int x, int z) {
            loaded.add(key(x, z));
        }

        @Override
        public boolean loaded(int x, int z) {
            return loaded.contains(key(x, z));
        }

        @Override
        public Optional<TerrainSampler.Observation> observe(int x, int z) {
            observed.add(key(x, z));
            return describe
                    ? Optional.of(new TerrainSampler.Observation(
                            "minecraft:overworld", x, z, "minecraft:plains", 64, false, ""))
                    : Optional.empty();
        }

        static long key(int x, int z) {
            return ((long) x << 32) ^ (z & 0xffffffffL);
        }
    }

    @Test
    void anUnloadedColumnIsNeverObserved() {
        Source source = new Source();
        source.load(0, 0);
        source.load(1, 0);

        TerrainSampler.Result result =
                TerrainSampler.sample(new TerrainGrid(), source, new TerrainSampler.Cursor(-1, -1), 0, 0, 1, 9, 10);

        assertEquals(2, result.sampled());
        assertEquals(7, result.skipped());
        assertEquals(2, source.observed.size(), "observe is called only for loaded columns");
        assertEquals(2, result.grid().size());
        assertTrue(result.grid().known(new TerrainCell.CellKey("minecraft:overworld", 0, 0)));
        assertFalse(result.grid().known(new TerrainCell.CellKey("minecraft:overworld", -1, -1)));
    }

    @Test
    void thePerTickBudgetLeavesTheCursorForTheNextTick() {
        Source source = new Source();
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                source.load(x, z);
            }
        }
        TerrainGrid grid = new TerrainGrid();
        TerrainSampler.Result first =
                TerrainSampler.sample(grid, source, TerrainSampler.Cursor.start(2), 0, 0, 2, 4, 1);
        assertEquals(4, first.sampled());
        assertEquals(4, grid.size());
        assertEquals(new TerrainSampler.Cursor(2, -2), first.next());

        TerrainSampler.Result second = TerrainSampler.sample(grid, source, first.next(), 0, 0, 2, 21, 2);
        assertEquals(21, second.sampled() + (second.skipped() == 0 ? 0 : 0));
        assertEquals(25, grid.size(), "the full square is covered over two ticks");
    }

    @Test
    void aLoadedColumnWithoutAnObservationIsSkippedNotStoredAsEmpty() {
        Source source = new Source();
        source.load(0, 0);
        source.describe = false;

        TerrainSampler.Result result =
                TerrainSampler.sample(new TerrainGrid(), source, new TerrainSampler.Cursor(0, 0), 0, 0, 1, 9, 3);

        assertEquals(0, result.sampled());
        assertTrue(result.grid().cells().isEmpty());
        assertEquals(9, result.skipped());
    }

    @Test
    void aLaterObservationRefreshesTheCellAndItsTick() {
        Source source = new Source();
        source.load(0, 0);
        source.load(1, 1);
        TerrainGrid grid = new TerrainGrid();
        TerrainSampler.sample(grid, source, new TerrainSampler.Cursor(-1, -1), 0, 0, 1, 9, 5);
        TerrainSampler.sample(grid, source, new TerrainSampler.Cursor(-1, -1), 0, 0, 1, 9, 9);

        assertEquals(
                10,
                grid.cell(new TerrainCell.CellKey("minecraft:overworld", 1, 1))
                                .orElseThrow()
                                .lastSeenTick()
                        + 1);
    }

    @Test
    void invalidArgumentsAreRejected() {
        assertThrows(() -> TerrainSampler.sample(null, new Source(), new TerrainSampler.Cursor(0, 0), 0, 0, 1, 1, 0));
        assertThrows(() ->
                TerrainSampler.sample(new TerrainGrid(), new Source(), new TerrainSampler.Cursor(0, 0), 0, 0, 0, 1, 0));
        assertThrows(() ->
                TerrainSampler.sample(new TerrainGrid(), new Source(), new TerrainSampler.Cursor(0, 0), 0, 0, 1, 0, 0));
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
