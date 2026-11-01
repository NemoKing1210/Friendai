package dev.friendai.agent.verify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.agent.goal.Criterion;
import dev.friendai.execution.queue.Guard;
import dev.friendai.execution.queue.GuardFacts;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class OutcomeCheckerTest {
    private static WorldSnapshot snap(Map<String, Integer> items, int slots, int owner) {
        return new WorldSnapshot(items, slots, owner, "minecraft:overworld");
    }

    private static GuardFacts facts(WorldSnapshot s) {
        return new GuardFacts() {
            @Override
            public int countItems(String id) {
                return s.items().getOrDefault(id, 0);
            }

            @Override
            public int emptySlots() {
                return s.emptySlots();
            }

            @Override
            public float healthFraction() {
                return 1f;
            }

            @Override
            public int ownerDistance() {
                return s.ownerDistance();
            }

            @Override
            public int countBlocks(String id, int radius) {
                return 0;
            }

            @Override
            public String dimension() {
                return s.dimension();
            }
        };
    }

    @Test
    void reportsItemGainsAndLosses() {
        WorldSnapshot before = snap(Map.of("minecraft:cobblestone", 5), 10, 3);
        WorldSnapshot after = snap(Map.of("minecraft:cobblestone", 2, "minecraft:oak_log", 12), 8, 3);
        Outcome o = OutcomeChecker.check(before, after, true, List.of(), facts(after));
        assertEquals(
                List.of("+12 minecraft:oak_log", "-3 minecraft:cobblestone", "empty slots 8 (was 10)"),
                o.facts().stream().map(Fact::text).toList());
        assertTrue(o.success());
        assertEquals("facts: +12 minecraft:oak_log, -3 minecraft:cobblestone, empty slots 8 (was 10)", o.factsLine());
    }

    @Test
    void capsFactsAtEightLargestFirst() {
        Map<String, Integer> gained = new HashMap<>();
        for (int i = 1; i <= 12; i++) {
            gained.put("minecraft:item_" + i, i);
        }
        WorldSnapshot before = snap(Map.of(), 20, 3);
        WorldSnapshot after = snap(gained, 20, 3);
        Outcome o = OutcomeChecker.check(before, after, true, List.of(), facts(after));
        assertEquals(8, o.facts().size());
        assertEquals("+12 minecraft:item_12", o.facts().get(0).text());
        assertEquals("+5 minecraft:item_5", o.facts().get(7).text());
    }

    @Test
    void textOnlyCriterionIsListedButDoesNotFailASuccessfulTask() {
        WorldSnapshot s = snap(Map.of(), 10, 3);
        Outcome o =
                OutcomeChecker.check(s, s, true, List.of(new Criterion("tell the owner", Optional.empty())), facts(s));
        assertTrue(o.success());
        assertEquals(List.of("tell the owner"), o.unmetCriteria());
        assertEquals("unmet: tell the owner", o.unmetLine());
    }

    @Test
    void criterionWithGuardIsCheckedAgainstAfterFacts() {
        Criterion logs = new Criterion("12 logs", Optional.of(new Guard.HasItem("minecraft:oak_log", 12)));
        WorldSnapshot before = snap(Map.of(), 10, 3);
        WorldSnapshot short5 = snap(Map.of("minecraft:oak_log", 5), 10, 3);
        Outcome missed = OutcomeChecker.check(before, short5, true, List.of(logs), facts(short5));
        assertFalse(missed.success());
        assertEquals(List.of("12 logs"), missed.unmetCriteria());
        WorldSnapshot enough = snap(Map.of("minecraft:oak_log", 12), 10, 3);
        assertTrue(OutcomeChecker.check(before, enough, true, List.of(logs), facts(enough))
                .success());
    }

    @Test
    void failedTaskIsNeverASuccess() {
        WorldSnapshot s = snap(Map.of(), 10, 3);
        assertFalse(OutcomeChecker.check(s, s, false, List.of(), facts(s)).success());
    }

    @Test
    void noChangeProducesNoFacts() {
        WorldSnapshot s = snap(Map.of("minecraft:stick", 4), 10, 3);
        Outcome o = OutcomeChecker.check(s, s, true, List.of(), facts(s));
        assertTrue(o.facts().isEmpty());
        assertEquals("", o.factsLine());
        assertEquals("", o.unmetLine());
    }

    @Test
    void largeOwnerDistanceChangeIsReported() {
        WorldSnapshot before = snap(Map.of(), 10, 3);
        WorldSnapshot after = snap(Map.of(), 10, 40);
        Outcome o = OutcomeChecker.check(before, after, true, List.of(), facts(after));
        assertEquals(
                List.of("owner 40 blocks away"),
                o.facts().stream().map(Fact::text).toList());
    }

    @Test
    void bodyDeltasAreReportedAndUnchangedBodyFactsStayQuiet() {
        WorldSnapshot before = new WorldSnapshot(Map.of(), 0, 2, "minecraft:overworld", 80, 18);
        WorldSnapshot hurt = new WorldSnapshot(Map.of(), 0, 2, "minecraft:overworld", 35, 12);
        List<String> hurtText = OutcomeChecker.check(before, hurt, true, List.of(), facts(hurt)).facts().stream()
                .map(Fact::text)
                .toList();
        assertTrue(hurtText.contains("health 35% (was 80%)"), hurtText.toString());
        assertTrue(hurtText.contains("food 12 (was 18)"), hurtText.toString());

        WorldSnapshot still = new WorldSnapshot(Map.of(), 0, 2, "minecraft:overworld", 35, 12);
        List<String> stillText = OutcomeChecker.check(hurt, still, true, List.of(), facts(still)).facts().stream()
                .map(Fact::text)
                .toList();
        assertTrue(stillText.isEmpty(), "an unchanged body produces no fact: " + stillText);

        WorldSnapshot unknownFood = new WorldSnapshot(Map.of(), 0, 2, "minecraft:overworld", 35, -1);
        List<String> unknownText =
                OutcomeChecker.check(hurt, unknownFood, true, List.of(), facts(unknownFood)).facts().stream()
                        .map(Fact::text)
                        .toList();
        assertTrue(unknownText.stream().noneMatch(text -> text.startsWith("food")), unknownText.toString());
    }
}
