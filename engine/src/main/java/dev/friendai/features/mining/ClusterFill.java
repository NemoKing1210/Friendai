package dev.friendai.features.mining;

import dev.friendai.features.navigation.pathing.Pos;
import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Grows a seed of matching cells into the six-connected cluster it belongs to: a tree's trunk for a felling, a vein of
 * the same ore for a pointed mine. The fill visits its neighbours in a fixed order, never exceeds the cap, and reports
 * the seed first, so the same world always expands the same way. The world is reached through {@link Cells}: the
 * Minecraft layer answers with the level, a test with an ASCII grid.
 */
public final class ClusterFill {
    /** What the fill may enter: a cell that matches the cluster and lies in a loaded chunk. */
    public interface Cells {
        boolean matches(int x, int y, int z);
    }

    /** Fixed neighbour order, so the same world always expands the same way. */
    private static final int[][] SIDES = {
        {1, 0, 0},
        {-1, 0, 0},
        {0, 1, 0},
        {0, -1, 0},
        {0, 0, 1},
        {0, 0, -1}
    };

    private ClusterFill() {}

    /**
     * @param seed the cells that are already known to belong, in the order they were seen; always part of the result
     * @param maxCells the bound on the result, seed included
     */
    public static List<Pos> expand(List<Pos> seed, Cells cells, int maxCells) {
        Set<Pos> found = new LinkedHashSet<>(seed);
        if (found.size() >= maxCells) {
            return found.stream().limit(maxCells).toList();
        }
        ArrayDeque<Pos> queue = new ArrayDeque<>(found);
        while (!queue.isEmpty()) {
            Pos cell = queue.poll();
            for (int[] side : SIDES) {
                if (found.size() >= maxCells) {
                    return List.copyOf(found);
                }
                Pos next = new Pos(cell.x() + side[0], cell.y() + side[1], cell.z() + side[2]);
                if (found.contains(next) || !cells.matches(next.x(), next.y(), next.z())) {
                    continue;
                }
                found.add(next);
                queue.add(next);
            }
        }
        return List.copyOf(found);
    }
}
