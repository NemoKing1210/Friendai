package dev.friendai.pathing;

/** Read-only view of the world as pathing cells. One view per search; implementations may cache. */
public interface CellView {

    Cell at(int x, int y, int z);

    default Cell at(Pos p) {
        return at(p.x(), p.y(), p.z());
    }
}
