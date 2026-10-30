package dev.friendai.features.mining.tree;

import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Grows a seed of observed logs into the tree that is really connected to it, so a felling that started at the crown
 * still covers the foot. The world is reached through {@link Cells}: the Minecraft layer answers with the level, a test
 * with an ASCII grid. The fill is six-connected, visits its neighbours in a fixed order and never exceeds the cap.
 */
public final class TrunkExpansion {
    /** What the fill may enter: a log that is in a loaded chunk and can be destroyed. */
    public interface Cells {
        boolean isLog(int x, int y, int z);

        boolean isLoaded(int x, int y, int z);
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

    private TrunkExpansion() {}

    /**
     * @param seed the observed logs, in the order they were seen; always part of the result
     * @param maxCells the bound on the result, {@code TreeFinder.MAX_SCANNED_LOGS}
     */
    public static List<TreeCell> expand(List<TreeCell> seed, Cells cells, int maxCells) {
        Set<TreeCell> found = new LinkedHashSet<>(seed);
        if (found.size() >= maxCells) {
            return List.copyOf(found);
        }
        ArrayDeque<TreeCell> queue = new ArrayDeque<>(found);
        while (!queue.isEmpty()) {
            TreeCell cell = queue.poll();
            for (int[] side : SIDES) {
                if (found.size() >= maxCells) {
                    return List.copyOf(found);
                }
                TreeCell next = new TreeCell(cell.x() + side[0], cell.y() + side[1], cell.z() + side[2]);
                if (found.contains(next)
                        || !cells.isLoaded(next.x(), next.y(), next.z())
                        || !cells.isLog(next.x(), next.y(), next.z())) {
                    continue;
                }
                found.add(next);
                queue.add(next);
            }
        }
        return List.copyOf(found);
    }
}
