package dev.friendai.knowledge.search;

import dev.friendai.crafting.obtain.OreDepths;
import java.util.OptionalInt;
import java.util.Set;

/** Resource-specific search strategies. This is policy data, not evidence about hidden locations. */
public record ResourceSearchProfile(
        String family, Set<String> dimensions, OptionalInt targetY, Set<Strategy> strategies) {
    public enum Strategy {
        AUTO,
        SURFACE,
        CAVE,
        MINE
    }

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

    /** Stone for the first tools needs a shallow quarry, not an ore expedition. */
    public boolean shallowStone() {
        return family.equals("minecraft:stone");
    }

    /** Conservative built-in policy; a catalogued target Y is a hint, not an optimality claim. */
    public static ResourceSearchProfile builtin(String family, String dimension) {
        OptionalInt depth = OreDepths.depthFor(family);
        Set<Strategy> allowed;
        if (family.equals("minecraft:stone") && dimension.equals("minecraft:overworld")) {
            allowed = Set.of(Strategy.MINE);
        } else if (depth.isPresent()) {
            allowed = family.equals("minecraft:coal") || family.equals("minecraft:raw_iron")
                    ? Set.of(Strategy.SURFACE, Strategy.CAVE, Strategy.MINE)
                    : Set.of(Strategy.CAVE, Strategy.MINE);
        } else if (ResourceFamily.isPlant(family)) {
            allowed = Set.of(Strategy.SURFACE);
        } else {
            allowed = Set.of();
        }
        return new ResourceSearchProfile(family, Set.of(dimension), depth, allowed);
    }
}
