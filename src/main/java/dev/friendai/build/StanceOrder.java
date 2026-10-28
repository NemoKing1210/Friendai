package dev.friendai.build;

import dev.friendai.pathing.Pos;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Where the companion may stand to fill a cell, in a fixed order so the same site always produces the same stance.
 * The target column is never a candidate, and the nearest ring comes first: an adjacent same-height cell is tried
 * before a diagonal, a step up or a step down.
 *
 * <p>The order is pure so a test can pin it; {@code BuildTask} filters the candidates by the world (free standing,
 * body clearance, reach) and asks the mover for the first one that survives.
 */
public final class StanceOrder {
    /** Compass directions, north first, clockwise, then the four diagonals. */
    private static final int[][] COMPASS = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}, {1, -1}, {1, 1}, {-1, 1}, {-1, -1}};

    /** Feet offsets, level first, then one up before one down so a step out of a pit wins over a drop. */
    private static final int[] VERTICAL = {0, 1, -1, 2, -2, 3, -3};

    private StanceOrder() {}

    /**
     * Candidate feet positions for filling {@code target}, ordered by ring, then height offset, then compass.
     *
     * @param feet the companion's current feet cell; the vertical offsets are relative to it
     * @param radius how many horizontal rings to emit; {@code 0} yields nothing
     */
    public static List<Pos> candidates(Pos target, Pos feet, int radius) {
        if (radius <= 0) {
            return List.of();
        }
        List<Pos> candidates = new ArrayList<>(radius * COMPASS.length * VERTICAL.length);
        Set<Long> seen = new HashSet<>();
        for (int ring = 1; ring <= radius; ring++) {
            for (int dy : VERTICAL) {
                for (int[] direction : COMPASS) {
                    int x = target.x() + direction[0] * ring;
                    int y = feet.y() + dy;
                    int z = target.z() + direction[1] * ring;
                    if (x == target.x() && y == target.y() && z == target.z()) {
                        continue;
                    }
                    Pos candidate = new Pos(x, y, z);
                    if (seen.add(candidate.pack())) {
                        candidates.add(candidate);
                    }
                }
            }
        }
        return List.copyOf(candidates);
    }
}
