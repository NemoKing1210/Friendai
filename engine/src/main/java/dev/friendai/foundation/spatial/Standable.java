package dev.friendai.foundation.spatial;

/** Pure standability and enterability predicates shared by pathing and its goal values. */
public final class Standable {
    private Standable() {}

    /**
     * Whether a body can occupy {@code node}: foot and head free (or a door it can open), held up by a support below,
     * or swimming in water. Never inside a hazard or an unloaded cell.
     */
    public static boolean at(CellView view, Node node) {
        Cell foot = view.at(node.pos());
        Cell head = view.at(node.head());
        if (!enterable(foot) || !enterable(head)) {
            return false;
        }
        if (foot.kind() == Cell.Kind.WATER) {
            return true;
        }
        return view.at(node.support()).supports();
    }

    /** Whether a cell can be entered at all: free, or a door a walk opens, and never a hazard. */
    public static boolean enterable(Cell cell) {
        if (cell.hazard()) {
            return false;
        }
        return cell.free() || cell.kind() == Cell.Kind.DOOR;
    }
}
