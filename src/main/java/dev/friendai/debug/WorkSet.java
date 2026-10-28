package dev.friendai.debug;

import java.util.List;
import java.util.Objects;

/** Immutable snapshot of one task-owned set. Counts describe the whole task set, including omitted wire cells. */
public record WorkSet(
        String label,
        WorkKind kind,
        int total,
        int done,
        int next,
        int pending,
        int skipped,
        boolean truncated,
        List<WorkCell> cells) {
    public static final int MAX_LABEL_CHARS = 80;

    public WorkSet {
        label = label == null ? "" : label;
        if (label.length() > MAX_LABEL_CHARS) {
            label = label.substring(0, MAX_LABEL_CHARS);
        }
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(cells, "cells");
        if (total < 0 || done < 0 || next < 0 || pending < 0 || skipped < 0
                || (long) done + next + pending + skipped != total) {
            throw new IllegalArgumentException("work-set counts must be nonnegative and sum to total");
        }
        cells = List.copyOf(cells);
        int cellDone = 0;
        int cellNext = 0;
        int cellPending = 0;
        int cellSkipped = 0;
        for (WorkCell cell : cells) {
            switch (cell.status()) {
                case DONE -> cellDone++;
                case NEXT -> cellNext++;
                case PENDING -> cellPending++;
                case SKIPPED -> cellSkipped++;
            }
        }
        if (cellDone > done || cellNext > next || cellPending > pending || cellSkipped > skipped) {
            throw new IllegalArgumentException("work-set cells exceed declared status counts");
        }
    }

    /** Copies this set with the selected wire cells while preserving full-set counts. */
    WorkSet withCells(List<WorkCell> selected) {
        return new WorkSet(label, kind, total, done, next, pending, skipped,
                truncated || selected.size() < cells.size(), selected);
    }

    WorkSet markTruncated() {
        return truncated ? this : new WorkSet(label, kind, total, done, next, pending, skipped, true, cells);
    }
}
