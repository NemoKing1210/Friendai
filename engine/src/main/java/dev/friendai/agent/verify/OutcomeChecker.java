package dev.friendai.agent.verify;

import dev.friendai.agent.goal.Criterion;
import dev.friendai.execution.queue.GuardFacts;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Compares the world before and after a task so the model reads facts, not just the task's own summary. */
public final class OutcomeChecker {
    public static final int MAX_FACTS = 8;
    private static final int OWNER_DISTANCE_CHANGE = 8;

    private OutcomeChecker() {}

    public static Outcome check(
            WorldSnapshot before,
            WorldSnapshot after,
            boolean taskSucceeded,
            List<Criterion> criteria,
            GuardFacts afterFacts) {
        List<String> unmet = new ArrayList<>();
        boolean checkableHolds = true;
        for (Criterion criterion : criteria) {
            if (criterion.check().isEmpty()) {
                unmet.add(criterion.text());
            } else if (!criterion.check().get().holds(afterFacts)) {
                unmet.add(criterion.text());
                checkableHolds = false;
            }
        }
        return new Outcome(taskSucceeded && checkableHolds, facts(before, after), unmet);
    }

    private static List<Fact> facts(WorldSnapshot before, WorldSnapshot after) {
        List<Fact> facts = new ArrayList<>();
        Set<String> ids = new HashSet<>(before.items().keySet());
        ids.addAll(after.items().keySet());
        List<Map.Entry<String, Integer>> deltas = new ArrayList<>();
        for (String id : ids) {
            int delta = after.items().getOrDefault(id, 0) - before.items().getOrDefault(id, 0);
            if (delta != 0) {
                deltas.add(Map.entry(id, delta));
            }
        }
        deltas.sort(Comparator.<Map.Entry<String, Integer>>comparingInt(e -> -Math.abs(e.getValue()))
                .thenComparing(Map.Entry::getKey));
        for (Map.Entry<String, Integer> delta : deltas) {
            facts.add(new Fact((delta.getValue() > 0 ? "+" : "") + delta.getValue() + " " + delta.getKey()));
        }
        if (before.emptySlots() != after.emptySlots()) {
            facts.add(new Fact("empty slots " + after.emptySlots() + " (was " + before.emptySlots() + ")"));
        }
        if (after.ownerDistance() >= 0
                && Math.abs(after.ownerDistance() - Math.max(0, before.ownerDistance())) >= OWNER_DISTANCE_CHANGE) {
            facts.add(new Fact("owner " + after.ownerDistance() + " blocks away"));
        }
        return facts.size() > MAX_FACTS ? facts.subList(0, MAX_FACTS) : facts;
    }
}
