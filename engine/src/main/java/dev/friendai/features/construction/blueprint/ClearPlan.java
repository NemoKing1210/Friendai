package dev.friendai.features.construction.blueprint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A deterministic work list for clearing an area: vegetation first, then snow, then stray solid blocks, and water
 * last, with the highest cell first inside a kind. Coordinates break ties, so the plan is reproducible. The cap
 * bounds one task; the cells it drops are counted in {@link #skipped()}.
 */
public record ClearPlan(List<ClearCell> cells, int skipped) {
    public ClearPlan {
        cells = List.copyOf(cells);
    }

    /** @param wanted the kinds to clear; empty means all of them; {@code max} clamps at zero */
    public static ClearPlan of(List<ClearCell> candidates, Set<ClearKind> wanted, int max) {
        int cap = Math.max(0, max);
        boolean all = wanted == null || wanted.isEmpty();
        Set<ClearCell> seen = new HashSet<>();
        List<ClearCell> selected = new ArrayList<>();
        int skipped = 0;
        for (ClearCell cell : candidates) {
            if (!seen.add(cell)) {
                continue;
            }
            if (!all && !wanted.contains(cell.kind())) {
                skipped++;
                continue;
            }
            selected.add(cell);
        }
        selected.sort(Comparator.comparingInt((ClearCell cell) -> rank(cell.kind()))
                .thenComparing(Comparator.comparingInt(ClearCell::y).reversed())
                .thenComparingInt(ClearCell::x)
                .thenComparingInt(ClearCell::z));
        if (selected.size() > cap) {
            skipped += selected.size() - cap;
            selected = new ArrayList<>(selected.subList(0, cap));
        }
        return new ClearPlan(selected, skipped);
    }

    /**
     * The index of the cell to work on next: the one nearest to the point among those of the first cell's kind, or -1
     * for an empty list. The plan lists the highest cell first, which sends a companion on the ground to the top of a
     * tree it cannot reach; the kind order stays, so snow and stray blocks still follow the vegetation.
     */
    public static int nextIndex(List<ClearCell> remaining, double x, double y, double z) {
        if (remaining.isEmpty()) {
            return -1;
        }
        ClearKind kind = remaining.get(0).kind();
        int best = -1;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (int i = 0; i < remaining.size(); i++) {
            ClearCell cell = remaining.get(i);
            if (cell.kind() != kind) {
                continue;
            }
            double dx = cell.x() + 0.5 - x;
            double dy = cell.y() + 0.5 - y;
            double dz = cell.z() + 0.5 - z;
            double distance = dx * dx + dy * dy + dz * dz;
            if (distance < bestDistance) {
                best = i;
                bestDistance = distance;
            }
        }
        return best;
    }

    private static int rank(ClearKind kind) {
        return switch (kind) {
            case VEGETATION -> 0;
            case SNOW -> 1;
            case BLOCK -> 2;
            case WATER -> 3;
        };
    }
}
