package dev.friendai.features.fire;

import dev.friendai.features.navigation.pathing.Pos;

/**
 * One cell that is burning, together with the world facts the adapter judged it by. The planner reads these
 * flags; it never looks at the world itself.
 *
 * @param reserved someone else's fire we must not touch: the companion's cooking campfire or the one it smokes a
 *     hive with
 * @param inPortalFrame breaking this cell would disturb a nether portal frame
 * @param waterSafe water may be poured here without flooding farmland, crops, redstone or a player
 * @param breakable the break list allows it and the level lets the companion act on it
 * @param reachable the companion can get within interaction reach
 * @param protectedCell the level or a place refuses the action; the reason outranks the other rails
 */
public record BurningCell(Pos pos, Kind kind, Rails rails) {

    public enum Kind {
        FIRE,
        SOUL_FIRE,
        LIT_CAMPFIRE
    }

    /** The judged facts for one cell. */
    public record Rails(
            boolean reserved,
            boolean inPortalFrame,
            boolean waterSafe,
            boolean breakable,
            boolean reachable,
            boolean protectedCell) {}
}
