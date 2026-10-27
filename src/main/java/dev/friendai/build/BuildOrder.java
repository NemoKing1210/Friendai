package dev.friendai.build;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * The order in which a companion places blueprint blocks. Without dependencies it is the old order: bottom layer
 * first; within a layer the air specs (which clear the ground) go first, then the blocks that stand on their own,
 * then the attachments that need a neighbour; each group is walked in a serpentine path from the side nearest the
 * companion.
 *
 * <p>A block whose state needs a solid neighbour ({@link Support#cell()}) is placed only after that neighbour, even
 * when the neighbour is in a higher layer — a wall torch waits for its wall, a hanging lantern for its beam. A cycle,
 * or a dependency the plan cannot satisfy, falls back to the plain priority order so the runtime deferral in
 * {@code BuildTask} still gets its chance.
 *
 * <p>The upper halves of double blocks (doors, beds' heads, tall plants) are dropped: placing the lower part creates
 * them.
 */
public final class BuildOrder {
    private BuildOrder() {}

    /**
     * What a planned block needs. {@code cell} is the planned cell that has to be placed first, or null when the
     * plan does not hold the support; {@code required} marks a block that cannot stand on its own, which is tried
     * after the free blocks even when {@code cell} is null.
     */
    public record Support(BlockSpec cell, boolean required) {
        public static final Support NONE = new Support(null, false);

        /** The block needs support that the blueprint does not contain. */
        public static Support needed() {
            return new Support(null, true);
        }

        /** The block needs {@code cell} to be solid first. */
        public static Support on(BlockSpec cell) {
            return new Support(cell, true);
        }
    }

    private record Cell(int x, int y, int z) {
        Cell(BlockSpec spec) {
            this(spec.x(), spec.y(), spec.z());
        }
    }

    public static List<BlockSpec> order(
            Blueprint blueprint, Function<BlockSpec, Support> support, int startX, int startZ) {
        Map<BlockSpec, Support> supports = new HashMap<>();
        for (BlockSpec spec : blueprint.blocks()) {
            Support found = support.apply(spec);
            supports.put(spec, found == null ? Support.NONE : found);
        }

        List<BlockSpec> priority = priority(blueprint, supports, startX, startZ);
        Map<BlockSpec, Integer> unmet = new HashMap<>();
        Map<Cell, List<BlockSpec>> dependents = new HashMap<>();
        Map<Cell, BlockSpec> placedCells = new HashMap<>();
        for (BlockSpec spec : priority) {
            if (!isAir(spec.state())) {
                placedCells.put(new Cell(spec), spec);
            }
        }
        for (BlockSpec spec : priority) {
            BlockSpec cell = supports.get(spec).cell();
            if (cell == null || isAir(cell.state())) {
                continue;
            }
            BlockSpec holder = placedCells.get(new Cell(cell));
            if (holder == null || holder.equals(spec)) {
                continue;
            }
            dependents.computeIfAbsent(new Cell(holder), c -> new ArrayList<>()).add(spec);
            unmet.merge(spec, 1, Integer::sum);
        }

        Map<BlockSpec, Integer> rank = new HashMap<>();
        for (int i = 0; i < priority.size(); i++) {
            rank.put(priority.get(i), i);
        }
        Comparator<BlockSpec> byRank = Comparator.comparingInt(rank::get);
        TreeSet<BlockSpec> ready = new TreeSet<>(byRank);
        for (BlockSpec spec : priority) {
            if (unmet.getOrDefault(spec, 0) == 0) {
                ready.add(spec);
            }
        }
        List<BlockSpec> ordered = new ArrayList<>(priority.size());
        Set<BlockSpec> emitted = new LinkedHashSet<>();
        while (emitted.size() < priority.size()) {
            if (ready.isEmpty()) {
                // A dependency cycle or an unreachable support: the lowest-priority remaining block goes next and
                // the placement loop defers it instead.
                for (BlockSpec spec : priority) {
                    if (!emitted.contains(spec) && ready.add(spec)) {
                        break;
                    }
                }
                if (ready.isEmpty()) {
                    break;
                }
            }
            BlockSpec next = ready.pollFirst();
            if (!emitted.add(next)) {
                continue;
            }
            ordered.add(next);
            for (BlockSpec dependent : dependents.getOrDefault(new Cell(next), List.of())) {
                if (unmet.merge(dependent, -1, Integer::sum) <= 0) {
                    ready.add(dependent);
                }
            }
        }
        return List.copyOf(ordered);
    }

    /** The order before dependencies: layer, then air / free / attachment, then the serpentine walk. */
    private static List<BlockSpec> priority(
            Blueprint blueprint, Map<BlockSpec, Support> supports, int startX, int startZ) {
        Map<Integer, List<BlockSpec>> layers = new TreeMap<>();
        for (BlockSpec spec : blueprint.blocks()) {
            if (isUpperHalf(spec.state())) {
                continue;
            }
            layers.computeIfAbsent(spec.y(), y -> new ArrayList<>()).add(spec);
        }
        List<BlockSpec> priority = new ArrayList<>(blueprint.blocks().size());
        for (List<BlockSpec> layer : layers.values()) {
            Comparator<BlockSpec> walk = serpentine(layer, startX, startZ);
            List<BlockSpec> air = new ArrayList<>();
            List<BlockSpec> free = new ArrayList<>();
            List<BlockSpec> attachments = new ArrayList<>();
            for (BlockSpec spec : layer) {
                if (isAir(spec.state())) {
                    air.add(spec);
                } else if (supports.get(spec).required()) {
                    attachments.add(spec);
                } else {
                    free.add(spec);
                }
            }
            air.sort(walk);
            free.sort(walk);
            attachments.sort(walk);
            priority.addAll(air);
            priority.addAll(free);
            priority.addAll(attachments);
        }
        return priority;
    }

    /** Positions ordered by a serpentine walk over the layer, starting at the corner nearest the given point. */
    private static Comparator<BlockSpec> serpentine(List<BlockSpec> layer, int startX, int startZ) {
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (BlockSpec spec : layer) {
            minX = Math.min(minX, spec.x());
            maxX = Math.max(maxX, spec.x());
            minZ = Math.min(minZ, spec.z());
            maxZ = Math.max(maxZ, spec.z());
        }
        boolean zAscending = Math.abs(startZ - minZ) <= Math.abs(startZ - maxZ);
        boolean firstRowFromWest = Math.abs(startX - minX) <= Math.abs(startX - maxX);
        List<Integer> rows = new ArrayList<>();
        for (int z = minZ; z <= maxZ; z++) {
            rows.add(z);
        }
        if (!zAscending) {
            rows = rows.reversed();
        }
        Map<Integer, Integer> rowIndex = new LinkedHashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            rowIndex.put(rows.get(i), i);
        }
        Map<Integer, Boolean> rowFromWest = new LinkedHashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            rowFromWest.put(rows.get(i), i % 2 == 0 ? firstRowFromWest : !firstRowFromWest);
        }
        boolean westFirst = firstRowFromWest;
        return Comparator.<BlockSpec>comparingInt(spec -> rowIndex.getOrDefault(spec.z(), 0))
                .thenComparingInt(spec -> rowFromWest.getOrDefault(spec.z(), westFirst) ? spec.x() : -spec.x());
    }

    private static boolean isUpperHalf(String state) {
        return state.contains("half=upper") || state.contains("part=head");
    }

    private static boolean isAir(String state) {
        int bracket = state.indexOf('[');
        return (bracket < 0 ? state : state.substring(0, bracket)).equals("minecraft:air");
    }
}
