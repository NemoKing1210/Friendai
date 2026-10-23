package dev.friedai.crafting.obtain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** What a scan found around the companion, in the shape the planner consumes. */
public record WorldFacts(
        Map<String, Integer> blocksNearby,
        Map<String, Integer> mobsNearby,
        Map<String, Integer> containerItems,
        Map<String, Integer> matureCrops,
        Set<String> stationsNearby,
        boolean exploreAllowed,
        boolean dimensionHasOre) {
    public WorldFacts {
        blocksNearby = copy(blocksNearby);
        mobsNearby = copy(mobsNearby);
        containerItems = copy(containerItems);
        matureCrops = copy(matureCrops);
        stationsNearby = stationsNearby == null
                ? Set.of()
                : Collections.unmodifiableSet(new LinkedHashSet<>(stationsNearby));
    }

    public static WorldFacts empty() {
        return new WorldFacts(Map.of(), Map.of(), Map.of(), Map.of(), Set.of(), false, true);
    }

    private static Map<String, Integer> copy(Map<String, Integer> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Integer> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key != null && value != null && value > 0) {
                copy.put(key, value);
            }
        });
        return Collections.unmodifiableMap(copy);
    }
}
