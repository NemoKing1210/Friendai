package dev.friendai.features.mining;

import dev.friendai.features.navigation.pathing.Cell;
import dev.friendai.features.navigation.pathing.CellView;
import dev.friendai.features.navigation.pathing.Pos;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Where the drop of one block would land, decided before the block is broken.
 *
 * <p>Vanilla spawns a block drop inside the broken cell itself ({@code Block.popResource}: the offset is at most a
 * quarter block on each axis) with a horizontal speed of at most 0.1 blocks a tick, so the cell directly below the
 * target always catches the fall; the item only slides afterwards, which is why a target on a rim gets a wider
 * platform. The model is pure: the caller supplies the world as a {@link CellView} and says which cells it may build
 * in.
 */
public final class DropRisk {

    /** How far below the target a drop may land and still count as reachable, in cells. */
    public static final int MAX_FALL = 3;

    private static final int[][] SIDES = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}};

    public enum Verdict {
        /** The drop lands somewhere the companion can take it; break the block. */
        SAFE,
        /** The drop has nowhere to land: place the catch cells first, then break the block. */
        NEEDS_PLATFORM,
        /** Nothing the companion can do keeps the drop; refuse and say what is missing. */
        IMPOSSIBLE
    }

    public enum Reason {
        LANDS_BELOW,
        CATCHES_DROP,
        DEEP_BELOW,
        DESTROYS_DROP,
        VOID_BELOW,
        PROTECTED_CELL,
        TOO_MANY_BLOCKS
    }

    /**
     * The decision for one target.
     *
     * @param landing the cell the drop lands in, when it lands somewhere useful; null otherwise
     * @param catchCells the cells to fill, in placement order (the cell under the target first); empty unless
     *     {@link Verdict#NEEDS_PLATFORM}
     */
    public record Report(Verdict verdict, Pos landing, List<Pos> catchCells, Reason reason) {
        public Report {
            catchCells = List.copyOf(catchCells);
        }

        public boolean safe() {
            return verdict == Verdict.SAFE;
        }
    }

    private DropRisk() {}

    /**
     * @param mayBuild whether the companion is allowed to place a block at the position; the view's own
     *     {@link Cell#placeable()} rule is applied on top, so a protected or non-replaceable cell stays refused
     * @param maxBlocks the most blocks one drop may cost; a wider platform is refused rather than half-built
     */
    public static Report assess(CellView view, Pos target, Predicate<Pos> mayBuild, int maxBlocks) {
        Pos below = target.below();
        Cell first = view.at(below);
        if (first.kind() == Cell.Kind.UNLOADED) {
            return new Report(Verdict.IMPOSSIBLE, null, List.of(), Reason.VOID_BELOW);
        }
        boolean destroys = false;
        for (int step = 0; step <= MAX_FALL; step++) {
            Pos at = new Pos(below.x(), below.y() - step, below.z());
            Cell cell = view.at(at);
            if (cell.kind() == Cell.Kind.UNLOADED) {
                return new Report(Verdict.IMPOSSIBLE, null, List.of(), Reason.VOID_BELOW);
            }
            if (cell.catchesDrops()) {
                if (!destroys) {
                    return new Report(
                            Verdict.SAFE, at, List.of(), step == 0 ? Reason.LANDS_BELOW : Reason.CATCHES_DROP);
                }
                break;
            }
            if (cell.destroysDrops()) {
                destroys = true;
                break;
            }
            if (!cell.free()) {
                // Closed like a door or taller than the companion, but with nothing to rest on.
                return new Report(Verdict.SAFE, at, List.of(), Reason.CATCHES_DROP);
            }
        }
        return platform(view, below, destroys, mayBuild, maxBlocks);
    }

    private static Report platform(CellView view, Pos below, boolean destroys, Predicate<Pos> mayBuild, int maxBlocks) {
        Reason why = destroys ? Reason.DESTROYS_DROP : Reason.DEEP_BELOW;
        if (!buildable(view, below, mayBuild)) {
            return new Report(Verdict.IMPOSSIBLE, below, List.of(), Reason.PROTECTED_CELL);
        }
        List<Pos> cells = new ArrayList<>(4);
        cells.add(below);
        if (rim(view, below)) {
            // The whole 3x3, corners included: a drop that slides off the platform leaves it diagonally too.
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    Pos ring = below.offset(dx, 0, dz);
                    if (buildable(view, ring, mayBuild)) {
                        cells.add(ring);
                    }
                }
            }
        }
        if (cells.size() > maxBlocks) {
            return new Report(Verdict.IMPOSSIBLE, below, List.of(), Reason.TOO_MANY_BLOCKS);
        }
        return new Report(Verdict.NEEDS_PLATFORM, null, cells, why);
    }

    /** A catch cell beside a sheer drop is an island: the drop slides off it, so the side has to be filled too. */
    private static boolean rim(CellView view, Pos below) {
        for (int[] side : SIDES) {
            Pos edge = below.offset(side[0], 0, side[2]);
            if (view.at(edge).kind() == Cell.Kind.UNLOADED) {
                // Nothing to build into and nothing to slide off: not an edge of this world.
                continue;
            }
            if (view.at(edge).catchesDrops() || view.at(edge.below()).catchesDrops()) {
                continue;
            }
            return true;
        }
        return false;
    }

    private static boolean buildable(CellView view, Pos pos, Predicate<Pos> mayBuild) {
        Cell cell = view.at(pos);
        if (cell.kind() == Cell.Kind.UNLOADED || !mayBuild.test(pos)) {
            return false;
        }
        // Lava is replaceable in vanilla, which is exactly the case the platform exists for; the view publishes no
        // placement flag for a fluid cell.
        return cell.placeable() || cell.kind() == Cell.Kind.LAVA;
    }
}
