package dev.friendai.features.mining;

import dev.friendai.features.navigation.pathing.Node;
import dev.friendai.features.navigation.pathing.Pos;

/** Immutable horizontal work area measured from the session's original node. */
public record MiningBounds(Node origin, int radius) {

    public MiningBounds {
        if (origin == null || radius < 0) {
            throw new IllegalArgumentException("Mining bounds require an origin and non-negative radius");
        }
    }

    public boolean contains(Pos pos) {
        return Math.max(Math.abs(pos.x() - origin.x()), Math.abs(pos.z() - origin.z())) <= radius;
    }
}
