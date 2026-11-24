package dev.friendai.execution.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class IdleRepairPolicyTest {
    private static final GuardFacts FACTS = new FakeGuardFacts(Map.of(), 0, 1f, 0, Map.of(), "minecraft:overworld");

    private static QueuedStep step(String tool, Guard... guards) {
        return new QueuedStep(
                tool, new JsonObject(), null, List.of(guards), GuardAction.WAIT, FailurePolicy.ASK, 1, List.of());
    }

    private static QueuedStep remedy(String item) {
        JsonObject args = new JsonObject();
        args.addProperty("item", item);
        args.addProperty("count", 4);
        return new QueuedStep("obtain_item", args, null, List.of(), GuardAction.WAIT, FailurePolicy.ASK, 1, List.of());
    }

    private static Set<Long> consent(JobQueue q) {
        return Set.of(q.plans().getFirst().id());
    }

    @Test
    void aMissingGuardedItemSelectsOneBoundedRepair() {
        JobQueue q = new JobQueue(QueueLimits.DEFAULTS);
        var added = q.addPlan(
                "place planks",
                List.of(step("place_block", new Guard.HasItem("minecraft:oak_planks", 4))),
                PlanOptions.DEFAULT);

        var repair = IdleRepairPolicy.select(q, FACTS, Set.of("obtain_item"), consent(q))
                .orElseThrow();

        assertEquals(added.jobIds().getFirst(), repair.blocked().id());
        assertEquals("obtain_item", repair.remedyTool());
        assertEquals("4 minecraft:oak_planks", repair.prerequisite().describe());
    }

    @Test
    void aToolGuardSelectsTheMappedPickaxe() {
        JobQueue q = new JobQueue(QueueLimits.DEFAULTS);
        q.addPlan(
                "mine ore",
                List.of(step("mine_blocks", new Guard.ToolAtLeast("minecraft:iron_ore"))),
                PlanOptions.DEFAULT);

        var repair = IdleRepairPolicy.select(
                        q, withoutTool(), Set.of("obtain_item"), consent(q), ToolRepairRule.vanillaPickaxes())
                .orElseThrow();

        assertEquals("1 minecraft:stone_pickaxe", repair.prerequisite().describe());
    }

    @Test
    void anExistingRemedyIsNotDuplicated() {
        JobQueue q = new JobQueue(QueueLimits.DEFAULTS);
        q.addDirect(remedy("minecraft:oak_planks"));
        q.addPlan(
                "place planks",
                List.of(step("place_block", new Guard.HasItem("minecraft:oak_planks", 4))),
                PlanOptions.DEFAULT);

        assertTrue(IdleRepairPolicy.select(q, FACTS, Set.of("obtain_item"), consent(q))
                .isEmpty());
    }

    @Test
    void runningJobsAndPlansWithoutConsentAreIgnored() {
        JobQueue q = new JobQueue(QueueLimits.DEFAULTS);
        var busy = q.addPlan(
                "busy",
                List.of(step("place_block", new Guard.HasItem("minecraft:oak_planks", 4))),
                PlanOptions.DEFAULT);
        q.markRunning(busy.jobIds().getFirst());
        q.addPlan(
                "no consent",
                List.of(step("place_block", new Guard.HasItem("minecraft:oak_planks", 4))),
                PlanOptions.DEFAULT);

        assertTrue(IdleRepairPolicy.select(q, FACTS, Set.of("obtain_item"), Set.of(busy.planId()))
                .isEmpty());
    }

    @Test
    void theHighestPriorityBlockedJobWinsDeterministically() {
        JobQueue q = new JobQueue(QueueLimits.DEFAULTS);
        QueuedStep low = new QueuedStep(
                "place_block",
                new JsonObject(),
                1,
                List.of(new Guard.HasItem("minecraft:dirt", 1)),
                GuardAction.WAIT,
                FailurePolicy.ASK,
                1,
                List.of());
        QueuedStep high = new QueuedStep(
                "place_block",
                new JsonObject(),
                9,
                List.of(new Guard.HasItem("minecraft:stone", 2)),
                GuardAction.WAIT,
                FailurePolicy.ASK,
                1,
                List.of());
        var added = q.addPlan("mixed", List.of(low, high), PlanOptions.DEFAULT);

        var repair = IdleRepairPolicy.select(q, FACTS, Set.of("obtain_item"), consent(q))
                .orElseThrow();

        assertEquals(added.jobIds().get(1), repair.blocked().id());
    }

    @Test
    void aJobAlreadyWaitingOnItsRemedyIsNotRepairedAgain() {
        JobQueue q = new JobQueue(QueueLimits.DEFAULTS);
        long blocked = q.addPlan(
                        "place planks",
                        List.of(step("place_block", new Guard.HasItem("minecraft:oak_planks", 4))),
                        PlanOptions.DEFAULT)
                .jobIds()
                .getFirst();
        assertTrue(q.insertPrerequisite(blocked, remedy("minecraft:oak_planks")).ok());

        assertTrue(IdleRepairPolicy.select(q, FACTS, Set.of("obtain_item"), consent(q))
                .isEmpty());
    }

    @Test
    void aFailedRemedyStaysAsTheOwnerVisibleBlockerInsteadOfRepeating() {
        JobQueue q = new JobQueue(QueueLimits.DEFAULTS);
        long blocked = q.addPlan(
                        "place planks",
                        List.of(step("place_block", new Guard.HasItem("minecraft:oak_planks", 4))),
                        PlanOptions.DEFAULT)
                .jobIds()
                .getFirst();
        assertTrue(q.insertPrerequisite(blocked, remedy("minecraft:oak_planks")).ok());
        long remedy = q.jobs().stream()
                .filter(job -> job.planId() == 0 && job.tool().equals("obtain_item"))
                .mapToLong(Job::id)
                .findFirst()
                .orElseThrow();
        q.fail(remedy, "nothing nearby");

        assertTrue(IdleRepairPolicy.select(q, FACTS, Set.of("obtain_item"), consent(q))
                .isEmpty());
    }

    @Test
    void aHeldJobIsNotRepaired() {
        JobQueue q = new JobQueue(QueueLimits.DEFAULTS);
        var added = q.addPlan(
                "place planks",
                List.of(step("place_block", new Guard.HasItem("minecraft:oak_planks", 4))),
                PlanOptions.DEFAULT);
        q.setHold(HoldKey.plan(added.planId()), QueueHold.pause("paused by the owner"));

        assertTrue(IdleRepairPolicy.select(q, FACTS, Set.of("obtain_item"), consent(q))
                .isEmpty());
    }

    @Test
    void aJobWaitingOutItsRetryDelayIsNotRepaired() {
        JobQueue q = new JobQueue(QueueLimits.DEFAULTS);
        QueuedStep retrying = new QueuedStep(
                "place_block",
                new JsonObject(),
                null,
                List.of(new Guard.HasItem("minecraft:oak_planks", 4)),
                GuardAction.WAIT,
                FailurePolicy.ASK,
                2,
                List.of());
        long blocked = q.addPlan("place planks", List.of(retrying), PlanOptions.DEFAULT)
                .jobIds()
                .getFirst();
        q.markRunning(blocked);
        q.fail(blocked, "no room");

        assertTrue(IdleRepairPolicy.select(q, FACTS, Set.of("obtain_item"), consent(q))
                .isEmpty());
    }

    @Test
    void aSecondUnmetGuardKeepsTheStepWaitingInsteadOfGatheringForNothing() {
        JobQueue q = new JobQueue(QueueLimits.DEFAULTS);
        q.addPlan(
                "wait for dawn",
                List.of(step("place_block", new Guard.TimeElapsed(100), new Guard.HasItem("minecraft:oak_planks", 4))),
                PlanOptions.DEFAULT);

        assertTrue(IdleRepairPolicy.select(q, FACTS, Set.of("obtain_item"), consent(q))
                .isEmpty());
    }

    @Test
    void aPlanWaitingForTheOwnerIsNotRepaired() {
        JobQueue q = new JobQueue(QueueLimits.DEFAULTS);
        var added = q.addPlan(
                "two steps",
                List.of(step("give_items"), step("place_block", new Guard.HasItem("minecraft:oak_planks", 4))),
                PlanOptions.DEFAULT);
        long first = added.jobIds().getFirst();
        q.markRunning(first);
        q.fail(first, "owner is away");

        assertEquals(PlanStatus.NEEDS_DECISION, QueuePolicy.planStatus(q, added.planId()));
        assertTrue(IdleRepairPolicy.select(q, FACTS, Set.of("obtain_item"), consent(q))
                .isEmpty());
    }

    @Test
    void aRefusedRepairDoesNotHideTheNextEligibleOne() {
        JobQueue q = new JobQueue(QueueLimits.DEFAULTS);
        var refused = q.addPlan(
                "the revoked plan",
                List.of(step("place_block", new Guard.HasItem("minecraft:dirt", 4))),
                PlanOptions.DEFAULT);
        var allowed = q.addPlan(
                "the owner plan",
                List.of(step("place_block", new Guard.HasItem("minecraft:oak_planks", 4))),
                PlanOptions.DEFAULT);

        var repair = IdleRepairPolicy.select(
                        q,
                        FACTS,
                        Set.of("obtain_item"),
                        Set.of(refused.planId(), allowed.planId()),
                        null,
                        candidate ->
                                candidate.blocked().id() == allowed.jobIds().getFirst())
                .orElseThrow();

        assertEquals(allowed.jobIds().getFirst(), repair.blocked().id());
        assertEquals("4 minecraft:oak_planks", repair.prerequisite().describe());
        assertTrue(refused.jobIds().getFirst() != repair.blocked().id(), "the refused step was skipped");
    }

    private static GuardFacts withoutTool() {
        return new GuardFacts() {
            public int countItems(String idOrTag) {
                return FACTS.countItems(idOrTag);
            }

            public int emptySlots() {
                return FACTS.emptySlots();
            }

            public float healthFraction() {
                return FACTS.healthFraction();
            }

            public int ownerDistance() {
                return FACTS.ownerDistance();
            }

            public int countBlocks(String idOrTag, int radius) {
                return FACTS.countBlocks(idOrTag, radius);
            }

            public String dimension() {
                return FACTS.dimension();
            }

            public Boolean canHarvest(String block) {
                return false;
            }
        };
    }
}
