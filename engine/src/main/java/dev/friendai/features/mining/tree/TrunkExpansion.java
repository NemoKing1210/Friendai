package dev.friendai.features.mining.tree;

import dev.friendai.features.mining.ClusterFill;
import dev.friendai.features.navigation.pathing.Pos;
import java.util.List;

/**
 * Grows a seed of observed logs into the tree that is really connected to it, so a felling that started at the crown
 * still covers the foot. The world is reached through {@link Cells}: the Minecraft layer answers with the level, a test
 * with an ASCII grid. The six-connected fill itself is {@link ClusterFill}, shared with vein mining; this class keeps
 * the tree-shaped types and tests.
 */
public final class TrunkExpansion {
    /** What the fill may enter: a log that is in a loaded chunk and can be destroyed. */
    public interface Cells {
        boolean isLog(int x, int y, int z);

        boolean isLoaded(int x, int y, int z);
    }

    private TrunkExpansion() {}

    /**
     * @param seed the observed logs, in the order they were seen; always part of the result
     * @param maxCells the bound on the result, {@code TreeFinder.MAX_SCANNED_LOGS}
     */
    public static List<TreeCell> expand(List<TreeCell> seed, Cells cells, int maxCells) {
        List<Pos> grown = ClusterFill.expand(
                seed.stream().map(cell -> new Pos(cell.x(), cell.y(), cell.z())).toList(),
                (x, y, z) -> cells.isLoaded(x, y, z) && cells.isLog(x, y, z),
                maxCells);
        return grown.stream()
                .map(cell -> new TreeCell(cell.x(), cell.y(), cell.z()))
                .toList();
    }
}
