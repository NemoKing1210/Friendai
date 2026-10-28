package dev.friendai.features.navigation.pathing;

/** One block position in the search grid. */
public record Pos(int x, int y, int z) {

    public static Pos of(int x, int y, int z) {
        return new Pos(x, y, z);
    }

    public Pos offset(int dx, int dy, int dz) {
        return new Pos(x + dx, y + dy, z + dz);
    }

    public Pos above() {
        return new Pos(x, y + 1, z);
    }

    public Pos below() {
        return new Pos(x, y - 1, z);
    }

    /** The position as a single key for hash maps; valid for the coordinate ranges a search uses. */
    public long pack() {
        return pack(x, y, z);
    }

    public static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }
}
