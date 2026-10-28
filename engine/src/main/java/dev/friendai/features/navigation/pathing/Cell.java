package dev.friendai.features.navigation.pathing;

/**
 * What one block position means for movement. {@code breakTicks} is the time to break it with the best tool the
 * companion carries, or −1 when it may not be broken at all (unbreakable, blacklisted, protected).
 */
public record Cell(Kind kind, int breakTicks, boolean falls, boolean placeable) {

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

    /** Whether the companion can be inside this cell. A closed door is only entered by opening it. */
    public boolean free() {
        return kind == Kind.AIR || kind == Kind.PASSABLE || kind == Kind.WATER || kind == Kind.CLIMBABLE;
    }

    public boolean hazard() {
        return kind == Kind.HAZARD || kind == Kind.LAVA || kind == Kind.UNLOADED;
    }
}
