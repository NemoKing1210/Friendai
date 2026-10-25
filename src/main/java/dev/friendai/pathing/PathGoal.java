package dev.friendai.pathing;

/** Where a search is trying to get to: a place, or a block the companion can act on from nearby. */
public interface PathGoal {

    boolean isGoal(Node n, CellView view);

    /** Estimated remaining cost from {@code n}, in ticks; never negative. */
    double heuristic(Node n, CostModel costs);

    /** Cells the search must never break, such as the blocks of a reach goal. */
    default boolean protects(Pos p) {
        return false;
    }
}
