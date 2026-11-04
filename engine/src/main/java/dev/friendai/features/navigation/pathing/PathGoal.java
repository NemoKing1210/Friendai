package dev.friendai.features.navigation.pathing;

import dev.friendai.foundation.spatial.CellView;
import dev.friendai.foundation.spatial.CostModel;
import dev.friendai.foundation.spatial.Node;
import dev.friendai.foundation.spatial.Pos;
import java.util.List;

/** Where a search is trying to get to: a place, or a block the companion can act on from nearby. */
public interface PathGoal {

    /** How many cells a debug overlay shows for one goal; a long target list is not worth the bytes. */
    int MAX_OVERLAY_CELLS = 64;

    boolean isGoal(Node n, CellView view);

    /** A short description for the movement overlay and the path log, without a class-name switch. */
    default String label() {
        return "goal";
    }

    /** The cells the goal asks for, for the movement overlay; at most {@link #MAX_OVERLAY_CELLS}. */
    default List<Pos> cells() {
        return List.of();
    }

    /** Estimated remaining cost from {@code n}, in ticks; never negative. */
    double heuristic(Node n, CostModel costs);

    /** Cells the search must never break, such as the blocks of a reach goal. */
    default boolean protects(Pos p) {
        return false;
    }
}
