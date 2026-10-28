package dev.friendai.tree;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Turns the logs of one tree and the cell the companion stands in into a felling order: the scaffold column beside the
 * trunk that costs the least climb, and the logs cut from it crown first. The reach arithmetic is the same the
 * Minecraft layer uses ({@link #REACH} from the eyes, {@link #EYE_HEIGHT} above the feet); the plan therefore only ever
 * cuts a log the companion can actually click from the stance it is standing on.
 *
 * <p>The plan never moves the column: the stance starts at the lowest height that reaches every log and only descends,
 * so the companion climbs once and the footing is removed after the last layer. A log no stance reaches (a long branch)
 * is counted in {@link FellPlan#unreachableLogs()} instead of being silently dropped.
 */
public final class FellPlanner {
    /** How far the eyes may be from a block centre and still act on it; the same value {@code Approach.REACH} uses. */
    public static final double REACH = 4.0;
    /** The eyes above the feet, as the companion's model reports it. */
    public static final double EYE_HEIGHT = 1.53;
    /** How far from the base footprint a climb column may stand. Ring 2 keeps a wide crown usable. */
    public static final int MAX_RING = 2;

    private static final double EPSILON = 1.0e-9;

    private FellPlanner() {}

    /**
     * @param input every log of the tree, in any order
     * @param stand the cell the companion stands in; its height is the ground when it is below the trunk's base
     * @param limit how many logs to cut at most, crown first; {@code 0} for the whole tree
     */
    public static FellPlan plan(List<TreeCell> input, TreeCell stand, int limit) {
        List<TreeCell> logs = input.stream().distinct().sorted().toList();
        if (logs.isEmpty()) {
            return new FellPlan(List.of(), List.of(), 0, 0);
        }
        int baseY = logs.stream().mapToInt(TreeCell::y).min().orElseThrow();
        int topY = logs.stream().mapToInt(TreeCell::y).max().orElseThrow();
        int groundFeetY = Math.min(stand.y(), baseY);

        List<FellPlan.Climb> climbs = new ArrayList<>();
        int unreachable = 0;
        for (TreeCell column : candidateColumns(logs, baseY, groundFeetY, stand)) {
            Built built = build(logs, column, groundFeetY, topY, baseY, limit);
            if (built == null) {
                continue;
            }
            climbs.add(built.climb());
            if (climbs.size() == 1) {
                unreachable = built.unreachable();
            }
        }
        if (climbs.isEmpty()) {
            return new FellPlan(List.of(), List.of(), logs.size(), 0);
        }
        return new FellPlan(climbs, climbs.get(0).logs(), logs.size(), unreachable);
    }

    private record Built(FellPlan.Climb climb, int unreachable) {}

    /**
     * Builds one climb, or returns null when the column cannot be used: a log stands in the band the companion would
     * climb through, so the scaffold would have to grow through the trunk or a branch.
     */
    private static Built build(List<TreeCell> logs, TreeCell column, int groundFeetY, int topY, int baseY,
            int limit) {
        Set<TreeCell> unreachable = new LinkedHashSet<>();
        int climbFeet = groundFeetY;
        for (TreeCell log : logs) {
            int lowest = lowestReaching(log, column, groundFeetY, topY);
            if (lowest < 0) {
                unreachable.add(log);
            } else {
                climbFeet = Math.max(climbFeet, lowest);
            }
        }
        for (TreeCell log : logs) {
            if (log.x() == column.x() && log.z() == column.z()
                    && log.y() >= groundFeetY && log.y() <= climbFeet + 2) {
                return null;
            }
        }

        int stance = climbFeet;
        List<FellPlan.Stage> stages = new ArrayList<>();
        List<TreeCell> current = new ArrayList<>(64);
        int currentFeet = stance;
        for (int y = topY; y >= baseY; y--) {
            for (TreeCell log : layer(logs, y, column, unreachable)) {
                if (!reaches(stance, column, log)) {
                    int next = highestReaching(log, column, stance - 1, groundFeetY);
                    if (next < 0) {
                        unreachable.add(log);
                        continue;
                    }
                    if (!current.isEmpty()) {
                        stages.add(new FellPlan.Stage(currentFeet, current));
                        current = new ArrayList<>(64);
                    }
                    stance = next;
                    currentFeet = stance;
                }
                current.add(log);
            }
        }
        if (!current.isEmpty()) {
            stages.add(new FellPlan.Stage(currentFeet, current));
        }
        return new Built(new FellPlan.Climb(column, groundFeetY, capped(stages, limit)), unreachable.size());
    }

    /** The cut stops after {@code limit} logs; the stages below the cut disappear with it. */
    private static List<FellPlan.Stage> capped(List<FellPlan.Stage> stages, int limit) {
        if (limit <= 0) {
            return stages;
        }
        List<FellPlan.Stage> limited = new ArrayList<>();
        int left = limit;
        for (FellPlan.Stage stage : stages) {
            if (left <= 0) {
                break;
            }
            int take = Math.min(left, stage.logs().size());
            limited.add(new FellPlan.Stage(stage.feetY(), stage.logs().subList(0, take)));
            left -= take;
        }
        return limited;
    }

    /** One layer, nearest log first, so the near face of a 2x2 trunk is removed before the far one. */
    private static List<TreeCell> layer(List<TreeCell> logs, int y, TreeCell column, Set<TreeCell> unreachable) {
        return logs.stream()
                .filter(log -> log.y() == y && !unreachable.contains(log))
                .sorted(Comparator.comparingDouble((TreeCell log) -> log.distanceSqr(column))
                        .thenComparingInt(TreeCell::x)
                        .thenComparingInt(TreeCell::z))
                .toList();
    }

    /** The lowest stance in {@code [from, to]} that reaches the log, or -1 when none does. */
    private static int lowestReaching(TreeCell log, TreeCell column, int from, int to) {
        for (int feetY = from; feetY <= to; feetY++) {
            if (reaches(feetY, column, log)) {
                return feetY;
            }
        }
        return -1;
    }

    /** The highest stance in {@code [from, to]} that reaches the log, or -1 when none does. */
    private static int highestReaching(TreeCell log, TreeCell column, int from, int to) {
        for (int feetY = from; feetY >= to; feetY--) {
            if (reaches(feetY, column, log)) {
                return feetY;
            }
        }
        return -1;
    }

    private static boolean reaches(int feetY, TreeCell column, TreeCell log) {
        double dx = (column.x() + 0.5) - (log.x() + 0.5);
        double dz = (column.z() + 0.5) - (log.z() + 0.5);
        double dy = feetY + EYE_HEIGHT - (log.y() + 0.5);
        return dx * dx + dy * dy + dz * dz <= REACH * REACH + EPSILON;
    }

    /** The cells beside the lowest layer: every ring-1 cell first, then ring 2, each nearest to the companion. */
    private static List<TreeCell> candidateColumns(List<TreeCell> logs, int baseY, int groundFeetY, TreeCell stand) {
        Set<Long> seen = new HashSet<>();
        List<Column> columns = new ArrayList<>();
        for (int ring = 1; ring <= MAX_RING; ring++) {
            for (TreeCell cell : logs) {
                if (cell.y() != baseY) {
                    continue;
                }
                for (int dx = -ring; dx <= ring; dx++) {
                    for (int dz = -ring; dz <= ring; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                            continue;
                        }
                        TreeCell column = new TreeCell(cell.x() + dx, groundFeetY, cell.z() + dz);
                        if (seen.add(column.columnKey())) {
                            columns.add(new Column(column, ring));
                        }
                    }
                }
            }
        }
        columns.sort(Comparator
                .comparingInt(Column::ring)
                .thenComparingDouble(column -> horizontalDistanceSqr(column.cell(), stand))
                .thenComparingInt(column -> column.cell().x())
                .thenComparingInt(column -> column.cell().z()));
        return columns.stream().map(Column::cell).toList();
    }

    private record Column(TreeCell cell, int ring) {}

    private static int horizontalDistanceSqr(TreeCell a, TreeCell b) {
        int dx = a.x() - b.x();
        int dz = a.z() - b.z();
        return dx * dx + dz * dz;
    }
}
