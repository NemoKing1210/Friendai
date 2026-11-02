package dev.friendai.execution.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class IdleRepairPolicyTest {
    private static Job job(long id, String tool, Guard guard, JobState state, long planId) {
        return new Job(
                id,
                planId,
                tool,
                new JsonObject(),
                0,
                false,
                id,
                state,
                guard == null ? List.of() : List.of(guard),
                GuardAction.WAIT,
                FailurePolicy.ASK,
                1,
                0,
                List.of(),
                "",
                0);
    }

    @Test
    void aMissingGuardedItemSelectsOneBoundedRepair() {
        Job blocked = job(2, "place_block", new Guard.HasItem("minecraft:oak_planks", 4), JobState.PENDING, 7);
        var repair = IdleRepairPolicy.select(List.of(blocked), FACTS, Set.of("obtain_item"), Set.of(7L))
                .orElseThrow();
        assertEquals(2, repair.blocked().id());
        assertEquals("obtain_item", repair.remedyTool());
        assertEquals("4 minecraft:oak_planks", repair.prerequisite().describe());
    }

    @Test
    void aToolGuardSelectsTheMappedPickaxe() {
        Job blocked = job(2, "mine_blocks", new Guard.ToolAtLeast("minecraft:iron_ore"), JobState.PENDING, 7);
        var repair = IdleRepairPolicy.select(
                        List.of(blocked), FACTS, Set.of("obtain_item"), Set.of(7L), ToolRepairRule.vanillaPickaxes())
                .orElseThrow();
        assertEquals("1 minecraft:stone_pickaxe", repair.prerequisite().describe());
    }

    @Test
    void anExistingRemedyIsNotDuplicated() {
        Job blocked = job(2, "place_block", new Guard.HasItem("minecraft:oak_planks", 4), JobState.PENDING, 7);
        JsonObject remedyArgs = new JsonObject();
        remedyArgs.addProperty("item", "minecraft:oak_planks");
        Job remedy = new Job(
                3,
                0,
                "obtain_item",
                remedyArgs,
                0,
                false,
                3,
                JobState.PENDING,
                List.of(),
                GuardAction.WAIT,
                FailurePolicy.ASK,
                1,
                0,
                List.of(),
                "",
                0);
        assertTrue(IdleRepairPolicy.select(List.of(blocked, remedy), FACTS, Set.of("obtain_item"), Set.of(7L))
                .isEmpty());
    }

    @Test
    void runningJobsAndPlansWithoutConsentAreIgnored() {
        Job running = job(1, "place_block", new Guard.HasItem("minecraft:oak_planks", 4), JobState.RUNNING, 7);
        Job noConsent = job(2, "place_block", new Guard.HasItem("minecraft:oak_planks", 4), JobState.PENDING, 8);
        assertTrue(IdleRepairPolicy.select(List.of(running, noConsent), FACTS, Set.of("obtain_item"), Set.of(7L))
                .isEmpty());
    }

    @Test
    void theHighestPriorityBlockedJobWinsDeterministically() {
        Job low = job(5, "place_block", new Guard.HasItem("minecraft:dirt", 1), JobState.PENDING, 7);
        Job high = job(9, "place_block", new Guard.HasItem("minecraft:stone", 2), JobState.PENDING, 7);
        var repair = IdleRepairPolicy.select(List.of(low, high), FACTS, Set.of("obtain_item"), Set.of(7L))
                .orElseThrow();
        assertEquals(5, repair.blocked().id());
    }

    /** No carried items and no stations: a definite missing for has_item. */
    private static final GuardFacts FACTS = new FakeGuardFacts(Map.of(), 0, 1f, 0, Map.of(), "minecraft:overworld");
}
