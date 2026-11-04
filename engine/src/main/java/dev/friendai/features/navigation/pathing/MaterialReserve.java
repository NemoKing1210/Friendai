package dev.friendai.features.navigation.pathing;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The items the planned work still needs, so no pillar, bridge, catch platform or drop guard spends them.
 *
 * <p>Composed, never accumulated: the plan's remaining steps and the running task both name what they are short of,
 * and the larger of the two wins, because a mining task's remaining target is part of the step that started it.
 * Immutable and read-only at use time, so a path search or a placement can ask it every tick without allocating.
 */
public final class MaterialReserve {
    public static final MaterialReserve EMPTY = new MaterialReserve(Map.of());

    private final Map<String, Integer> reserved;

    private MaterialReserve(Map<String, Integer> reserved) {
        this.reserved = reserved;
    }

    /** A reserve of the item counts given; entries that reserve nothing are dropped. */
    public static MaterialReserve of(Map<String, Integer> held) {
        if (held == null || held.isEmpty()) {
            return EMPTY;
        }
        Map<String, Integer> kept = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : held.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null) {
                continue;
            }
            if (entry.getValue() > 0) {
                kept.put(entry.getKey().strip(), entry.getValue());
            }
        }
        return kept.isEmpty() ? EMPTY : new MaterialReserve(Map.copyOf(kept));
    }

    /** The larger need of the two sources, per item: the plan's remaining steps and the running task's own need. */
    public static MaterialReserve compose(Map<String, Integer> planNeeds, Map<String, Integer> taskNeeds) {
        if (planNeeds == null || planNeeds.isEmpty()) {
            return of(taskNeeds);
        }
        if (taskNeeds == null || taskNeeds.isEmpty()) {
            return of(planNeeds);
        }
        Map<String, Integer> merged = new LinkedHashMap<>(planNeeds);
        for (Map.Entry<String, Integer> entry : taskNeeds.entrySet()) {
            merged.merge(entry.getKey(), entry.getValue(), Math::max);
        }
        return of(merged);
    }

    /** How many of this item are still owed to the planned work. */
    public int reserved(String itemId) {
        Integer count = reserved.get(itemId == null ? "" : itemId.strip());
        return count == null ? 0 : count;
    }

    /** How many carried items of this kind are free to be placed: the carried count minus the reserve, never negative. */
    public int free(String itemId, int held) {
        return Math.max(0, held - reserved(itemId));
    }

    /** Whether at least one carried item of this kind may be spent right now. */
    public boolean spendable(String itemId, int held) {
        return free(itemId, held) > 0;
    }

    /** The reserve after one item of this kind was spent; a fully covered item stops being reserved. */
    public MaterialReserve spend(String itemId) {
        String id = itemId == null ? "" : itemId.strip();
        int left = reserved(id);
        if (left <= 0) {
            return this;
        }
        Map<String, Integer> next = new LinkedHashMap<>(reserved);
        if (left == 1) {
            next.remove(id);
        } else {
            next.put(id, left - 1);
        }
        return of(next);
    }

    public Map<String, Integer> asMap() {
        return reserved;
    }

    public boolean isEmpty() {
        return reserved.isEmpty();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof MaterialReserve that && reserved.equals(that.reserved);
    }

    @Override
    public int hashCode() {
        return reserved.hashCode();
    }

    @Override
    public String toString() {
        return "MaterialReserve" + reserved;
    }
}
