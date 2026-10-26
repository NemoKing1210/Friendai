package dev.friendai.knowledge.search;

import java.util.OptionalInt;
import java.util.Set;
import dev.friendai.crafting.obtain.OreDepths;

/** Resource-specific search strategies. This is policy data, not evidence about hidden locations. */
public record ResourceSearchProfile(String family, Set<String> dimensions, OptionalInt targetY, Set<Strategy> strategies) {
    public enum Strategy { AUTO, SURFACE, CAVE, MINE }

    public ResourceSearchProfile {
        if (family == null || family.isBlank()) throw new IllegalArgumentException("family must not be blank");
        dimensions = Set.copyOf(dimensions == null ? Set.of() : dimensions);
        targetY = targetY == null ? OptionalInt.empty() : targetY;
        strategies = Set.copyOf(strategies == null ? Set.of() : strategies);
    }

    public boolean allows(String dimension, Strategy strategy) {
        return (dimensions.isEmpty() || dimensions.contains(dimension))
                && (strategies.contains(Strategy.AUTO) || strategies.contains(strategy));
    }

    /** Conservative built-in policy; a catalogued target Y is a hint, not an optimality claim. */
    public static ResourceSearchProfile builtin(String family, String dimension) {
        OptionalInt depth = OreDepths.depthFor(family);
        Set<Strategy> allowed;
        if (depth.isPresent()) {
            allowed = family.equals("minecraft:coal") || family.equals("minecraft:raw_iron")
                    ? Set.of(Strategy.SURFACE, Strategy.CAVE, Strategy.MINE)
                    : Set.of(Strategy.CAVE, Strategy.MINE);
        } else if (family != null && (family.endsWith("_log") || family.endsWith("_stem")
                || family.endsWith("_leaves") || family.endsWith("_sapling") || family.endsWith("_flower"))) {
            allowed = Set.of(Strategy.SURFACE);
        } else {
            allowed = Set.of();
        }
        return new ResourceSearchProfile(family, Set.of(dimension), depth, allowed);
    }
}
