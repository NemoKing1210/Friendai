package dev.friendai.features.mining.excavation;

import java.util.Map;

/** The stop condition of an excavation: a block cap and/or an exact item goal counted from taken drops. */
public record ExcavationGoal(int count, String untilItem, int untilCount) {
    public boolean itemGoal() {
        return untilItem != null && !untilItem.isBlank() && untilCount > 0;
    }

    public boolean countReached(int broken) {
        return broken >= count;
    }

    public int taken(Map<String, Integer> taken) {
        if (!itemGoal() || taken == null) {
            return 0;
        }
        return taken.getOrDefault(untilItem, 0);
    }

    public boolean itemReached(Map<String, Integer> taken) {
        return itemGoal() && taken(taken) >= untilCount;
    }

    public boolean reached(int broken, Map<String, Integer> taken) {
        return countReached(broken) || itemReached(taken);
    }

    public String describe() {
        return itemGoal() ? count + " blocks or " + untilCount + " " + untilItem : count + " blocks";
    }
}
