package dev.friendai.build;

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

    private static int rank(ClearKind kind) {
        return switch (kind) {
            case VEGETATION -> 0;
            case SNOW -> 1;
            case BLOCK -> 2;
            case WATER -> 3;
        };
    }
}
