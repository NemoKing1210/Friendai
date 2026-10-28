package dev.friendai.features.crafting;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the companion would craft, what it is short of, and which stations the plan uses or still has to
 * craft. {@code missing} maps an ingredient label to how many are lacking, in first-missing order.
 */
public record CraftPlan(
        List<CraftStep> steps, Map<String, Integer> missing, Set<Station> stationsUsed, Set<Station> stationsToCraft) {
    public CraftPlan {
        steps = List.copyOf(steps);
        missing = Collections.unmodifiableMap(new LinkedHashMap<>(missing));
        stationsUsed = Collections.unmodifiableSet(new LinkedHashSet<>(stationsUsed));
        stationsToCraft = Collections.unmodifiableSet(new LinkedHashSet<>(stationsToCraft));
    }

    /** True when the plan can run from the current inventory (nothing missing). */
    public boolean ok() {
        return missing.isEmpty();
    }
}
