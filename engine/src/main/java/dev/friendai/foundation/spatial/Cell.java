package dev.friendai.foundation.spatial;

/**
 * What one block position means for movement. {@code breakTicks} is the time to break it with the best tool the
 * companion carries, or −1 when it may not be broken at all (unbreakable, blacklisted, protected).
 *
 * <p>{@code top} is the height of the surface a body stands on, in blocks above the cell's floor: 1 for a full
 * block, 0.5 for a bottom slab, 0.0625 for a carpet. The grid puts the node above the support cell, so a shorter
 * support leaves the feet lower than the node's level; the walker plans its climbs from that real height instead of
 * assuming every standable cell is a full block.
 */
public record Cell(Kind kind, int breakTicks, boolean falls, boolean placeable, double top) {

    /** The top of a full block. */
    public static final double FULL_TOP = 1.0;
    /** The top of a half-height block such as a bottom slab. */
    public static final double HALF_TOP = 0.5;

    /**
     * A cell with the standing height of its kind. The Minecraft adapter passes the collision shape's real top; this
     * keeps pure fixtures and callers that only classify a cell readable.
     */
    public Cell(Kind kind, int breakTicks, boolean falls, boolean placeable) {
        this(kind, breakTicks, falls, placeable, defaultTop(kind));
    }

    private static double defaultTop(Kind kind) {
        return switch (kind) {
            case SOLID, CLIMBABLE -> FULL_TOP;
            case PARTIAL -> HALF_TOP;
            default -> 0.0;
        };
    }

    public enum Kind {
        /** Nothing here, or a replaceable plant that does not block movement. */
        AIR,
        /** Walk-through but not replaceable: flowers, rails, torches, open doors. */
        PASSABLE,
        /** A full collision box you can stand on. */
        SOLID,
        /** Standable, lower than a full block: slabs, paths, farmland, snow layers. */
        PARTIAL,
        /** Collision higher than one block: fences, walls. Never stand on, never step over. */
        TALL,
        WATER,
        LAVA,
        /** Ladder, vine, scaffolding, twisting vines. */
        CLIMBABLE,
        /** A closed wooden door or gate the companion can open. */
        DOOR,
        /** Fire, magma, cactus, sweet berry bush, powder snow, dripstone, campfire. */
        HAZARD,
        /** Not loaded, or outside the world height. */
        UNLOADED
    }

    public static final Cell AIR = new Cell(Kind.AIR, -1, false, true);
    public static final Cell UNLOADED = new Cell(Kind.UNLOADED, -1, false, false);

    /** Whether the cell may be broken on the way when it blocks movement. */
    public boolean breakable() {
        if (breakTicks < 0) {
            return false;
        }
        return kind == Kind.SOLID
                || kind == Kind.PARTIAL
                || kind == Kind.TALL
                || kind == Kind.CLIMBABLE
                || kind == Kind.DOOR
                || kind == Kind.PASSABLE;
    }

    /** Whether standing on this cell is possible. */
    public boolean supports() {
        return kind == Kind.SOLID || kind == Kind.PARTIAL || kind == Kind.CLIMBABLE;
    }

    /**
     * Whether an item that fell into this cell would rest here. A ladder is standable but has no top to catch a drop
     * with, so this is deliberately narrower than {@link #supports()}.
     */
    public boolean catchesDrops() {
        return kind == Kind.SOLID || kind == Kind.PARTIAL || kind == Kind.TALL || kind == Kind.DOOR;
    }

    /** The pure mirror of {@code LevelCellView.destroysItems}: lava, fire and a cactus destroy a drop. */
    public boolean destroysDrops() {
        return kind == Kind.LAVA || kind == Kind.HAZARD;
    }

    /** Whether the companion can be inside this cell. A closed door is only entered by opening it. */
    public boolean free() {
        return kind == Kind.AIR || kind == Kind.PASSABLE || kind == Kind.WATER || kind == Kind.CLIMBABLE;
    }

    public boolean hazard() {
        return kind == Kind.HAZARD || kind == Kind.LAVA || kind == Kind.UNLOADED;
    }
}
