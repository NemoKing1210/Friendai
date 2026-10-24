package dev.friendai.tool;

/**
 * Who may open the companion's inventory window: the owner, close enough and in the same dimension, with the
 * companion alive. Pure Java; the caller supplies the facts.
 */
public final class InventoryAccess {
    public enum Denial {
        NONE,
        NOT_OWNER,
        OTHER_DIMENSION,
        TOO_FAR,
        GONE
    }

    /** How far the owner may stand from the companion and still open its inventory. */
    public static final double MAX_DISTANCE = 8.0;

    private InventoryAccess() {}

    /** The first failing check, in the order gone, not owner, other dimension, too far. */
    public static Denial check(boolean isOwner, boolean sameLevel, boolean alive, double distance) {
        if (!alive) {
            return Denial.GONE;
        }
        if (!isOwner) {
            return Denial.NOT_OWNER;
        }
        if (!sameLevel) {
            return Denial.OTHER_DIMENSION;
        }
        return distance <= MAX_DISTANCE ? Denial.NONE : Denial.TOO_FAR;
    }
}
