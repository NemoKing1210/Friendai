package dev.friendai.features.mining.tree;

import java.util.ArrayList;
import java.util.List;

/**
 * Picks the leaf a stranded drop rests on. A felling cuts the crown first, and a log cut above the canopy lands on the
 * leaves instead of the ground: breaking exactly the support leaf lets the item fall where the sweep can reach it. The
 * decision is pure; the caller checks protection, the landing and the caps before it breaks anything.
 */
public final class CanopyRescue {
    /** What the decision may break: leaves only, never a placed block or a trunk log. */
    public interface Cells {
        boolean isLeaf(int x, int y, int z);
    }

    private CanopyRescue() {}

    /**
     * @param supports the blocks the stuck drops rest on, nearest first; the caller resolves the exact support cell,
     *     because an item a hair inside its block would name the cell below it
     * @return those of them that are leaves, deduplicated and capped
     */
    public static List<TreeCell> targets(List<TreeCell> supports, Cells cells, int max) {
        List<TreeCell> targets = new ArrayList<>(Math.min(max, supports.size()));
        for (TreeCell support : supports) {
            if (targets.size() >= max) {
                break;
            }
            if (cells.isLeaf(support.x(), support.y(), support.z()) && !targets.contains(support)) {
                targets.add(support);
            }
        }
        return List.copyOf(targets);
    }
}
