package dev.friendai.foundation.spatial;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The items planned work needs plus any separate support share for a verified return trip.
 *
 * <p>Plan and task work needs compose by maximum, because a mining task's remaining target is part of the step that
 * started it. Return supports are added separately; only a return navigator may spend that share. Immutable and
 * read-only at use time, so a path search or placement can ask it every tick without allocating.
 */
public final class MaterialReserve {
    public static final MaterialReserve EMPTY = new MaterialReserve(Map.of(), Map.of());

    private final Map<String, Integer> work;
    private final Map<String, Integer> travel;
    private final Map<String, Integer> reserved;

    private MaterialReserve(Map<String, Integer> work, Map<String, Integer> travel) {
        this.work = work;
        this.travel = travel;
        if (travel.isEmpty()) {
            this.reserved = work;
        } else {
            Map<String, Integer> total = new LinkedHashMap<>(work);
            travel.forEach((id, count) -> total.merge(id, count, (workCount, travelCount) ->
                    (int) Math.min(Integer.MAX_VALUE, (long) workCount + travelCount)));
            this.reserved = Map.copyOf(total);
        }
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
        return kept.isEmpty() ? EMPTY : new MaterialReserve(Map.copyOf(kept), Map.of());
    }

    /** The larger need of the two sources, per item: the plan's remaining steps and the running task's own need. */
    public static MaterialReserve compose(Map<String, Integer> planNeeds, Map<String, Integer> taskNeeds) {
        return compose(planNeeds, taskNeeds, Map.of());
    }

    /** Work needs compose by maximum; the independent return-trip share is added on top. */
    public static MaterialReserve compose(
            Map<String, Integer> planNeeds, Map<String, Integer> taskNeeds, Map<String, Integer> travelNeeds) {
        Map<String, Integer> workNeeds = maximum(planNeeds, taskNeeds);
        MaterialReserve workReserve = of(workNeeds);
        MaterialReserve travelReserve = of(travelNeeds);
        if (travelReserve.isEmpty()) return workReserve;
        return new MaterialReserve(workReserve.work, travelReserve.work);
    }

    /** The work share alone, for the return trip that is allowed to spend its separate travel allocation. */
    public MaterialReserve forReturnLeg() {
        return travel.isEmpty() ? this : of(work);
    }

    /** How many items of this kind belong to planned or active work, excluding the return-trip share. */
    public int workReserved(String itemId) {
        return count(work, itemId);
    }

    /** How many items of this kind are set aside only for a verified return route. */
    public int travelReserved(String itemId) {
        return count(travel, itemId);
    }

    /** Total reserved count, including the separate return-trip share. */
    public int reserved(String itemId) {
        return count(reserved, itemId);
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
        int travelLeft = travelReserved(id);
        int workLeft = workReserved(id);
        if (travelLeft <= 0 && workLeft <= 0) {
            return this;
        }
        Map<String, Integer> nextTravel = new LinkedHashMap<>(travel);
        Map<String, Integer> nextWork = new LinkedHashMap<>(work);
        if (travelLeft > 0) {
            if (travelLeft == 1) nextTravel.remove(id);
            else nextTravel.put(id, travelLeft - 1);
        } else {
            if (workLeft == 1) nextWork.remove(id);
            else nextWork.put(id, workLeft - 1);
        }
        return new MaterialReserve(Map.copyOf(nextWork), Map.copyOf(nextTravel));
    }

    public Map<String, Integer> asMap() {
        return reserved;
    }

    public Map<String, Integer> workAsMap() {
        return work;
    }

    public Map<String, Integer> travelAsMap() {
        return travel;
    }

    public boolean isEmpty() {
        return reserved.isEmpty();
    }

    private static int count(Map<String, Integer> values, String itemId) {
        Integer count = values.get(itemId == null ? "" : itemId.strip());
        return count == null ? 0 : count;
    }

    private static Map<String, Integer> maximum(Map<String, Integer> first, Map<String, Integer> second) {
        MaterialReserve normalizedFirst = of(first);
        MaterialReserve normalizedSecond = of(second);
        if (normalizedFirst.isEmpty()) return normalizedSecond.work;
        if (normalizedSecond.isEmpty()) return normalizedFirst.work;
        Map<String, Integer> merged = new LinkedHashMap<>(normalizedFirst.work);
        normalizedSecond.work.forEach((id, count) -> merged.merge(id, count, Math::max));
        return Map.copyOf(merged);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof MaterialReserve that && work.equals(that.work) && travel.equals(that.travel);
    }

    @Override
    public int hashCode() {
        return 31 * work.hashCode() + travel.hashCode();
    }

    @Override
    public String toString() {
        return "MaterialReserve" + reserved;
    }
}
