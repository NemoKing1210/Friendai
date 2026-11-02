package dev.friendai.execution.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PlanRepairTest {
    private static final class Facts implements GuardFacts {
        final Map<String, Integer> items = new HashMap<>();
        Boolean harvest;
        Boolean station;
        int food = -1;
        int slots = 20;
        Boolean online;

        @Override
        public int countItems(String idOrTag) {
            return items.getOrDefault(idOrTag, 0);
        }

        @Override
        public int emptySlots() {
            return slots;
        }

        @Override
        public float healthFraction() {
            return 1f;
        }

        @Override
        public int ownerDistance() {
            return 0;
        }

        @Override
        public int countBlocks(String idOrTag, int radius) {
            return 0;
        }

        @Override
        public String dimension() {
            return "minecraft:overworld";
        }

        @Override
        public Boolean canHarvest(String block) {
            return harvest;
        }

        @Override
        public Boolean stationWithin(String station, int radius) {
            return this.station;
        }

        @Override
        public int foodLevel() {
            return food;
        }

        @Override
        public Boolean ownerOnline() {
            return online;
        }
    }

    private static final Set<String> TOOLS = Set.of("obtain_item", "craft_item", "get_food", "store_items");

    @Test
    void aSatisfiedItemNeedsNoStep() {
        Facts facts = new Facts();
        facts.items.put("minecraft:iron_pickaxe", 1);
        PlanRepair.Advice advice = PlanRepair.advise(
                List.of(new PlanRepair.Item("minecraft:iron_pickaxe", 1, "obtain_item")), facts, TOOLS, true, 0);

        assertEquals(PlanRepair.Outcome.SATISFIED, advice.findings().getFirst().outcome());
        assertTrue(advice.steps().isEmpty());
        assertTrue(advice.blockers().isEmpty());
    }

    @Test
    void aMissingItemBecomesOneBoundedObtainStep() {
        PlanRepair.Advice advice = PlanRepair.advise(
                List.of(new PlanRepair.Item("minecraft:iron_pickaxe", 1, "obtain_item")), new Facts(), TOOLS, true, 0);

        assertEquals(1, advice.steps().size());
        assertEquals("obtain_item", advice.steps().getFirst().tool());
        assertEquals(
                "minecraft:iron_pickaxe",
                advice.steps().getFirst().args().get("item").getAsString());
        assertEquals(1, advice.steps().getFirst().args().get("count").getAsInt());
        assertEquals(PlanRepair.Outcome.MISSING, advice.findings().getFirst().outcome());
    }

    @Test
    void unknownWorldEvidenceIsNotMissing() {
        PlanRepair.Advice advice =
                PlanRepair.advise(List.of(new PlanRepair.Harvest("minecraft:iron_ore")), new Facts(), TOOLS, true, 0);

        assertEquals(PlanRepair.Outcome.UNKNOWN, advice.findings().getFirst().outcome());
        assertTrue(advice.steps().isEmpty(), "unknown must not trigger acquisition");
        assertTrue(advice.blockers().isEmpty(), "unknown is not a blocker yet");
    }

    @Test
    void ownerOfflineIsAWaitNotARepair() {
        Facts facts = new Facts();
        facts.online = false;
        PlanRepair.Advice advice = PlanRepair.advise(List.of(new PlanRepair.OwnerOnline()), facts, TOOLS, true, 0);

        assertEquals(
                PlanRepair.Outcome.UNSUPPORTED, advice.findings().getFirst().outcome());
        assertTrue(advice.steps().isEmpty());
        assertEquals(1, advice.blockers().size());
        assertTrue(advice.blockers().getFirst().contains("owner online"));
    }

    @Test
    void autoRepairOffGuaranteesNoInsertion() {
        PlanRepair.Advice advice = PlanRepair.advise(
                List.of(new PlanRepair.Item("minecraft:iron_pickaxe", 1, "obtain_item")), new Facts(), TOOLS, false, 0);

        assertEquals(PlanRepair.Outcome.MISSING, advice.findings().getFirst().outcome());
        assertTrue(advice.steps().isEmpty());
        assertTrue(advice.findings().getFirst().reason().contains("auto repair is off"));
    }

    @Test
    void anUnavailableRemedyToolIsUnsupported() {
        PlanRepair.Advice advice = PlanRepair.advise(
                List.of(new PlanRepair.Item("minecraft:iron_pickaxe", 1, "obtain_item")),
                new Facts(),
                Set.of("eat"),
                true,
                0);

        assertEquals(
                PlanRepair.Outcome.UNSUPPORTED, advice.findings().getFirst().outcome());
        assertTrue(advice.steps().isEmpty());
        assertEquals(1, advice.blockers().size());
    }

    @Test
    void perBlockerAndPerPlanCapsHold() {
        Facts facts = new Facts();
        List<PlanRepair.Prerequisite> four = List.of(
                new PlanRepair.Item("minecraft:a", 1, "obtain_item"),
                new PlanRepair.Item("minecraft:b", 1, "obtain_item"),
                new PlanRepair.Item("minecraft:c", 1, "obtain_item"),
                new PlanRepair.Item("minecraft:d", 1, "obtain_item"));
        PlanRepair.Advice distinct = PlanRepair.advise(four, facts, TOOLS, true, 0);
        assertEquals(4, distinct.steps().size(), "four distinct blockers are four steps");
        assertFalse(distinct.truncated());

        List<PlanRepair.Prerequisite> seventeen = new java.util.ArrayList<>();
        for (int i = 0; i < PlanRepair.MAX_PER_PLAN + 1; i++) {
            seventeen.add(new PlanRepair.Item("minecraft:item_" + i, 1, "obtain_item"));
        }
        PlanRepair.Advice planCapped = PlanRepair.advise(seventeen, facts, TOOLS, true, 0);
        assertEquals(PlanRepair.MAX_PER_PLAN, planCapped.steps().size());
        assertTrue(planCapped.truncated());
        assertEquals(1, planCapped.blockers().size());

        PlanRepair.Advice alreadyFull = PlanRepair.advise(
                List.of(new PlanRepair.Item("minecraft:a", 1, "obtain_item")),
                facts,
                TOOLS,
                true,
                PlanRepair.MAX_PER_PLAN);
        assertTrue(alreadyFull.steps().isEmpty());
        assertTrue(alreadyFull.truncated());
    }

    @Test
    void duplicateRemediesAreDeduplicated() {
        PlanRepair.Advice advice = PlanRepair.advise(
                List.of(
                        new PlanRepair.Item("minecraft:iron_pickaxe", 1, "obtain_item"),
                        new PlanRepair.Item("minecraft:iron_pickaxe", 1, "obtain_item")),
                new Facts(),
                TOOLS,
                true,
                0);
        assertEquals(1, advice.steps().size());
    }

    @Test
    void foodAndSpaceUseTheirOwnFacts() {
        Facts facts = new Facts();
        facts.food = 4;
        facts.slots = 0;
        PlanRepair.Advice advice = PlanRepair.advise(
                List.of(new PlanRepair.Food(10, "get_food"), new PlanRepair.Space(2, "store_items")),
                facts,
                TOOLS,
                true,
                0);
        assertEquals(2, advice.steps().size());
        assertEquals("get_food", advice.steps().get(0).tool());
        assertEquals("store_items", advice.steps().get(1).tool());
        assertFalse(advice.steps().isEmpty());
    }
}
