package dev.friendai.features.knowledge.search;

import dev.friendai.foundation.spatial.Pos;

/** The compass direction an eye of ender leaves toward the nearest stronghold; never a stronghold position. */
public final class StrongholdClue {
    private StrongholdClue() {}

    public static SearchPlanner.Heading heading(Pos from, Pos to) {
        if (from == null || to == null) throw new IllegalArgumentException("positions are required");
        long dx = (long) to.x() - from.x();
        long dz = (long) to.z() - from.z();
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx >= 0 ? SearchPlanner.Heading.EAST : SearchPlanner.Heading.WEST;
        }
        return dz >= 0 ? SearchPlanner.Heading.SOUTH : SearchPlanner.Heading.NORTH;
    }
}
