package dev.friendai.structure;

import java.util.Map;
import java.util.Set;

/**
 * What the planner sees of the world at a site.
 *
 * @param footprintFree no obstruction in the footprint other than correct body blocks
 * @param entityInFootprint the companion or a player stands where blocks would go
 * @param protectedArea the companion may not change this area
 * @param correctBodyBlocks body offsets whose world block already matches
 * @param outcomeAlreadyPresent set for block outcomes only; a mob nearby counts toward the limit instead
 * @param nearbySpawned entities of the outcome type already near the site
 * @param bag item id to count in the companion's inventory
 */
public record StructureSnapshot(
        boolean footprintFree,
        boolean entityInFootprint,
        boolean protectedArea,
        boolean inEnd,
        Set<Offset> correctBodyBlocks,
        boolean outcomeAlreadyPresent,
        int nearbySpawned,
        int maxNearbySpawned,
        Map<String, Integer> bag) {
    public StructureSnapshot {
        correctBodyBlocks = Set.copyOf(correctBodyBlocks);
        bag = Map.copyOf(bag);
    }
}
