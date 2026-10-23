package dev.friendai.build;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * The order in which a companion places blueprint blocks: bottom layer first; within a layer the air specs (which
 * clear the ground) go first, then the blocks that stand on their own, then the attachments that need a neighbour;
 * each group is walked in a serpentine path from the side nearest the companion.
 *
 * <p>The upper halves of double blocks (doors, beds' heads, tall plants) are dropped: placing the lower part creates
 * them.
 */
public final class BuildOrder {
    private BuildOrder() {}

    public static List<BlockSpec> order(
            Blueprint blueprint, Predicate<String> needsSupport, int startX, int startZ) {
        Map<Integer, List<BlockSpec>> layers = new TreeMap<>();
        for (BlockSpec spec : blueprint.blocks()) {
            if (isUpperHalf(spec.state())) {
                continue;
            }
            layers.computeIfAbsent(spec.y(), y -> new ArrayList<>()).add(spec);
        }
        List<BlockSpec> ordered = new ArrayList<>(blueprint.blocks().size());
        for (List<BlockSpec> layer : layers.values()) {
            Comparator<BlockSpec> walk = serpentine(layer, startX, startZ);
            List<BlockSpec> air = new ArrayList<>();
            List<BlockSpec> free = new ArrayList<>();
            List<BlockSpec> attachments = new ArrayList<>();
            for (BlockSpec spec : layer) {
                if (isAir(spec.state())) {
                    air.add(spec);
                } else if (needsSupport.test(spec.state())) {
                    attachments.add(spec);
                } else {
                    free.add(spec);
                }
            }
            air.sort(walk);
            free.sort(walk);
            attachments.sort(walk);
            ordered.addAll(air);
            ordered.addAll(free);
            ordered.addAll(attachments);
        }
        return List.copyOf(ordered);
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
