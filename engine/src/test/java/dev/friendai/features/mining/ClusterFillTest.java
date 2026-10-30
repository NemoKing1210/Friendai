package dev.friendai.features.mining;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.features.navigation.pathing.Pos;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ClusterFillTest {

    private static ClusterFill.Cells world(String... layers) {
        // layers[0] is the top row: y grows downward in the literal, so a cell is (x, height - y, z).
        int height = layers.length;
        return (x, y, z) -> {
            if (z != 0) {
                return false;
            }
            int row = height - 1 - y;
            return row >= 0
                    && row <= layers.length - 1
                    && x >= 0
                    && x < layers[row].length()
                    && layers[row].charAt(x) == '#';
        };
    }

    @Test
    void growsTheWholeConnectedClusterOnly() {
        // .#.
        // ###
        // .#.
        ClusterFill.Cells cells = world(".#.", "###", ".#.");
        List<Pos> grown = ClusterFill.expand(List.of(new Pos(1, 1, 0)), cells, 16);
        assertEquals(
                Set.of(new Pos(1, 1, 0), new Pos(0, 1, 0), new Pos(2, 1, 0), new Pos(1, 2, 0), new Pos(1, 0, 0)),
                new HashSet<>(grown));
    }

    @Test
    void theSeedComesFirstAndOrderIsFixed() {
        ClusterFill.Cells cells = world("###");
        List<Pos> first = ClusterFill.expand(List.of(new Pos(1, 0, 0)), cells, 16);
        List<Pos> second = ClusterFill.expand(List.of(new Pos(1, 0, 0)), cells, 16);
        assertEquals(first, second);
        assertEquals(new Pos(1, 0, 0), first.get(0));
    }

    @Test
    void stopsAtTheCap() {
        ClusterFill.Cells cells = world("#######");
        List<Pos> grown = ClusterFill.expand(List.of(new Pos(0, 0, 0)), cells, 3);
        assertEquals(3, grown.size());
        assertEquals(new Pos(0, 0, 0), grown.get(0));
    }

    @Test
    void neverLeavesTheMatchingCells() {
        ClusterFill.Cells cells = world("#.#");
        List<Pos> grown = ClusterFill.expand(List.of(new Pos(0, 0, 0)), cells, 16);
        assertEquals(List.of(new Pos(0, 0, 0)), grown);
    }

    @Test
    void theSeedIsKeptEvenWhenTheCapIsFull() {
        ClusterFill.Cells cells = world("#");
        List<Pos> grown = ClusterFill.expand(List.of(new Pos(0, 0, 0)), cells, 1);
        assertEquals(List.of(new Pos(0, 0, 0)), grown);
        assertFalse(grown.isEmpty());
        assertTrue(grown.size() <= 1);
    }
}
