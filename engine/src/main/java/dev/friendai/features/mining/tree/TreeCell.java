package dev.friendai.features.mining.tree;

/**
 * A block position in the pure tree layer. The build and farming modules keep their own integer cell types for the
 * same reason: the order of a felling must be testable without a Minecraft server.
 */
public record TreeCell(int x, int y, int z) implements Comparable<TreeCell> {
    public TreeCell above() {
        return new TreeCell(x, y + 1, z);
    }

    public TreeCell below() {
        return new TreeCell(x, y - 1, z);
    }

    /** Packs x and z into one key, so a column is compared without its height. */
    public long columnKey() {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    /** Squared distance between block centres. */
    public double distanceSqr(TreeCell other) {
        double dx = (x + 0.5) - (other.x + 0.5);
        double dy = (y + 0.5) - (other.y + 0.5);
        double dz = (z + 0.5) - (other.z + 0.5);
        return dx * dx + dy * dy + dz * dz;
    }

    @Override
    public int compareTo(TreeCell other) {
        int byX = Integer.compare(x, other.x);
        if (byX != 0) {
            return byX;
        }
        int byY = Integer.compare(y, other.y);
        return byY != 0 ? byY : Integer.compare(z, other.z);
    }

    @Override
    public String toString() {
        return x + " " + y + " " + z;
    }
}
