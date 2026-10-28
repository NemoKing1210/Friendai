package dev.friendai.debug;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Pure deterministic selection of task work cells for the bounded movement-debug payload. */
public final class WorkSetBudget {
    public static final int MAX_WORK_SETS = 8;
    public static final int MAX_WORK_CELLS = 256;
    public static final int MAX_CELLS_PER_SET = 256;

    private WorkSetBudget() {}

    public static List<WorkSet> select(List<WorkSet> sets, int originX, int originY, int originZ) {
        if (sets == null || sets.isEmpty()) {
            return List.of();
        }
        int setCount = Math.min(sets.size(), MAX_WORK_SETS);
        List<List<WorkCell>> selected = new ArrayList<>(setCount);
        int[] counts = new int[setCount];
        int totalSelected = 0;

        // Reserve each set's first NEXT cell before filling the shared budget.
        for (int i = 0; i < setCount; i++) {
            List<WorkCell> picked = new ArrayList<>();
            WorkCell next = sets.get(i).cells().stream()
                    .filter(cell -> cell.status() == WorkStatus.NEXT)
                    .min(Comparator.comparingLong((WorkCell cell) -> distanceSquared(cell, originX, originY, originZ))
                            .thenComparingInt(WorkCell::x)
                            .thenComparingInt(WorkCell::y)
                            .thenComparingInt(WorkCell::z))
                    .orElse(null);
            if (next != null && totalSelected < MAX_WORK_CELLS) {
                picked.add(next);
                counts[i]++;
                totalSelected++;
            }
            selected.add(picked);
        }

        List<IndexedCell> candidates = new ArrayList<>();
        for (int i = 0; i < setCount; i++) {
            WorkSet set = sets.get(i);
            for (WorkCell cell : set.cells()) {
                if (selected.get(i).contains(cell)) {
                    continue;
                }
                candidates.add(new IndexedCell(i, cell, distanceSquared(cell, originX, originY, originZ)));
            }
        }
        candidates.sort(Comparator.comparingInt(
                        (IndexedCell item) -> priority(item.cell().status()))
                .thenComparingLong(item -> item.cell().status() == WorkStatus.PENDING ? item.distance() : 0L)
                .thenComparingInt(IndexedCell::setIndex)
                .thenComparingInt(item -> item.cell().x())
                .thenComparingInt(item -> item.cell().y())
                .thenComparingInt(item -> item.cell().z()));

        for (IndexedCell candidate : candidates) {
            int index = candidate.setIndex();
            if (totalSelected >= MAX_WORK_CELLS || counts[index] >= MAX_CELLS_PER_SET) {
                continue;
            }
            selected.get(index).add(candidate.cell());
            counts[index]++;
            totalSelected++;
        }

        List<WorkSet> result = new ArrayList<>(setCount);
        for (int i = 0; i < setCount; i++) {
            WorkSet original = sets.get(i);
            WorkSet bounded = original.withCells(selected.get(i));
            if (sets.size() > MAX_WORK_SETS && i == setCount - 1) {
                bounded = bounded.markTruncated();
            }
            result.add(bounded);
        }
        return List.copyOf(result);
    }

    private static int priority(WorkStatus status) {
        return switch (status) {
            case NEXT -> 0;
            case PENDING -> 1;
            case SKIPPED -> 2;
            case DONE -> 3;
        };
    }

    private static long distanceSquared(WorkCell cell, int x, int y, int z) {
        long dx = (long) cell.x() - x;
        long dy = (long) cell.y() - y;
        long dz = (long) cell.z() - z;
        return dx * dx + dy * dy + dz * dz;
    }

    private record IndexedCell(int setIndex, WorkCell cell, long distance) {}
}
