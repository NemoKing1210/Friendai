package dev.friendai.placed;

import dev.friendai.pathing.Pos;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/** Nearest-first selection over a placement ledger; the block registry stays in the Minecraft facade. */
public final class PlacedQuery {

    /**
     * @param entries the selected entries, nearest first
     * @param total how many entries matched before the limit
     * @param truncated true when {@code total} is larger than the returned list
     */
    public record Result(List<PlacedBlock> entries, int total, boolean truncated) {
        public Result {
            entries = List.copyOf(entries);
            if (total < entries.size()) {
                total = entries.size();
            }
            truncated = truncated || total > entries.size();
        }
    }

    private PlacedQuery() {}

    /** Selects entries in one dimension within {@code radius} of {@code origin}, nearest first. */
    public static Result find(
            PlacedBook book,
            String dimension,
            Pos origin,
            int radius,
            Predicate<PlacedBlock> filter,
            int limit) {
        Objects.requireNonNull(book, "book");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(filter, "filter");
        double radiusSquared = radius <= 0 ? Double.MAX_VALUE : (double) radius * radius;
        List<PlacedBlock> matched = new ArrayList<>();
        for (PlacedBlock block : book.inDimension(dimension)) {
            if (filter.test(block) && distanceSquared(origin, block.pos()) <= radiusSquared) {
                matched.add(block);
            }
        }
        matched.sort(Comparator
                .comparingDouble((PlacedBlock block) -> distanceSquared(origin, block.pos()))
                .thenComparingInt(block -> block.pos().x())
                .thenComparingInt(block -> block.pos().y())
                .thenComparingInt(block -> block.pos().z()));
        int total = matched.size();
        int keep = Math.max(0, Math.min(limit, total));
        return new Result(new ArrayList<>(matched.subList(0, keep)), total, total > keep);
    }

    public static double distanceSquared(Pos origin, Pos pos) {
        double dx = pos.x() - origin.x();
        double dy = pos.y() - origin.y();
        double dz = pos.z() - origin.z();
        return dx * dx + dy * dy + dz * dz;
    }
}
