package dev.friendai.features.navigation.pathing;

/** A foot position the companion can stand in; the head cell is one above it. */
public record Node(int x, int y, int z) {

    public Pos pos() {
        return new Pos(x, y, z);
    }

    public Pos head() {
        return new Pos(x, y + 1, z);
    }

    /** The cell that holds the companion up: the one below the feet. */
    public Pos support() {
        return new Pos(x, y - 1, z);
    }

    public long pack() {
        return Pos.pack(x, y, z);
    }
}
