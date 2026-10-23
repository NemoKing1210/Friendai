package dev.friendai.crafting.obtain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The steps to get an item, what could not be obtained, and how many steps the plan has. */
public record ObtainPlan(List<ObtainStep> steps, Map<String, Integer> missing, int estimatedSteps) {
    public ObtainPlan {
        steps = List.copyOf(steps);
        missing = Collections.unmodifiableMap(new LinkedHashMap<>(missing));
    }

    /** True when every needed item can be obtained. */
    public boolean ok() {
        return missing.isEmpty();
    }
}
