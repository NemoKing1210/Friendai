package dev.friendai.execution.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Unknown evidence must never decide a branch, a skip, a repair or a repeat. */
class GuardTruthTest {
    /** One {@link GuardFacts} whose every uncertain answer is configurable. */
    private static final class Facts implements GuardFacts {
        Boolean blockHere;
        Boolean ownerOnline;
        Boolean daytime;
        String weather = "clear";
        String biome = "minecraft:plains";
        String dimension = "minecraft:overworld";
        Boolean station;
        Boolean canHarvest;
        Boolean planDone;
        Boolean goalMet;
        Boolean blocksWithin;
        int food = -1;
        int xp = -1;

        @Override
        public int countItems(String idOrTag) {
            return 0;
        }

        @Override
        public int emptySlots() {
            return 0;
        }

        @Override
        public float healthFraction() {
            return 1;
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
        public Boolean hasBlocksOrNull(String idOrTag, int radius, int count) {
            return blocksWithin;
        }

        @Override
        public String dimension() {
            return dimension;
        }

        @Override
        public Boolean blockAtOrNull(String idOrTag, int x, int y, int z) {
            return blockHere;
        }

        @Override
        public Boolean ownerOnline() {
            return ownerOnline;
        }

        @Override
        public Boolean daytime() {
            return daytime;
        }

        @Override
        public String weather() {
            return weather;
        }

        @Override
        public String biome() {
            return biome;
        }

        @Override
        public Boolean stationWithin(String station, int radius) {
            return this.station;
        }

        @Override
        public Boolean canHarvest(String block) {
            return canHarvest;
        }

        @Override
        public Boolean planDone(long planId) {
            return planDone;
        }

        @Override
        public Boolean goalMet(long goalId) {
            return goalMet;
        }

        @Override
        public int foodLevel() {
            return food;
        }

        @Override
        public int xpLevel() {
            return xp;
        }
    }

    private static Guard.BlockAt blockAt() {
        return new Guard.BlockAt("minecraft:stone", 4, 64, 4);
    }

    @Test
    void notOfUnknownStaysUnknownInsteadOfBecomingTrue() {
        Facts facts = new Facts();
        facts.blockHere = null;

        assertEquals(Guard.Truth.UNKNOWN, blockAt().evaluate(facts));
        Guard not = new Guard.Not(blockAt());
        assertEquals(Guard.Truth.UNKNOWN, not.evaluate(facts), "not(null) is not a confirmed absence");
        assertFalse(not.holds(facts), "a guard holds only on confirmed true");

        facts.blockHere = true;
        assertEquals(Guard.Truth.FALSE, not.evaluate(facts));
        assertFalse(not.holds(facts));
        facts.blockHere = false;
        assertEquals(Guard.Truth.TRUE, not.evaluate(facts));
        assertTrue(not.holds(facts));
    }

    @Test
    void groupsComposeMissingEvidenceByKleeneRules() {
        Facts facts = new Facts();
        facts.blockHere = null;
        facts.ownerOnline = true;

        Guard allWithUnknown = new Guard.AllOf(List.of(new Guard.OwnerOnline(), new Guard.Not(blockAt())));
        assertEquals(Guard.Truth.UNKNOWN, allWithUnknown.evaluate(facts));
        facts.ownerOnline = false;
        assertEquals(Guard.Truth.FALSE, allWithUnknown.evaluate(facts), "a confirmed false settles all_of");

        facts.ownerOnline = null;
        Guard anyWithUnknown = new Guard.AnyOf(List.of(new Guard.OwnerOnline(), new Guard.Not(blockAt())));
        assertEquals(Guard.Truth.UNKNOWN, anyWithUnknown.evaluate(facts));
        facts.ownerOnline = true;
        assertEquals(Guard.Truth.TRUE, anyWithUnknown.evaluate(facts), "a confirmed true settles any_of");

        facts.blockHere = false;
        facts.ownerOnline = false;
        assertEquals(
                Guard.Truth.TRUE,
                new Guard.AnyOf(List.of(blockAt(), new Guard.OwnerOffline())).evaluate(facts),
                "a known alternative settles the group even when another child is unknown");
    }

    @Test
    void absentOwnerRecordsClocksWeatherAndVitalsAreUnknownNotFalse() {
        Facts facts = new Facts();

        assertEquals(Guard.Truth.UNKNOWN, new Guard.OwnerOnline().evaluate(facts));
        assertEquals(Guard.Truth.UNKNOWN, new Guard.OwnerOffline().evaluate(facts));
        assertEquals(Guard.Truth.UNKNOWN, new Guard.Daytime().evaluate(facts));
        assertEquals(Guard.Truth.UNKNOWN, new Guard.Nighttime().evaluate(facts));
        assertEquals(Guard.Truth.UNKNOWN, new Guard.StationWithin("crafting_table", 8).evaluate(facts));
        assertEquals(Guard.Truth.UNKNOWN, new Guard.ToolAtLeast("minecraft:stone").evaluate(facts));
        assertEquals(Guard.Truth.UNKNOWN, new Guard.PlanDone(3).evaluate(facts));
        assertEquals(Guard.Truth.UNKNOWN, new Guard.GoalMet(3).evaluate(facts));
        assertEquals(Guard.Truth.UNKNOWN, new Guard.FoodAtLeast(10).evaluate(facts));
        assertEquals(Guard.Truth.UNKNOWN, new Guard.XpAtLeast(3).evaluate(facts));

        facts.weather = "";
        facts.biome = "";
        facts.dimension = "";
        assertEquals(Guard.Truth.UNKNOWN, new Guard.WeatherIs("rain").evaluate(facts));
        assertEquals(Guard.Truth.UNKNOWN, new Guard.InBiome("minecraft:desert").evaluate(facts));
        assertEquals(Guard.Truth.UNKNOWN, new Guard.InDimension("minecraft:the_nether").evaluate(facts));

        facts.ownerOnline = false;
        facts.daytime = true;
        facts.food = 4;
        facts.xp = 9;
        assertEquals(Guard.Truth.TRUE, new Guard.OwnerOffline().evaluate(facts));
        assertEquals(Guard.Truth.FALSE, new Guard.Nighttime().evaluate(facts));
        assertEquals(Guard.Truth.FALSE, new Guard.FoodAtLeast(10).evaluate(facts));
        assertEquals(Guard.Truth.TRUE, new Guard.XpAtLeast(3).evaluate(facts));
    }

    @Test
    void aConditionalOnUnknownEvidenceSelectsNoBranchAndKeepsItsPlace() {
        PlanDefinition plan = new PlanDefinition(List.of(new PlanNode.Conditional(
                new Guard.Not(blockAt()), List.of(action("then_action")), List.of(action("else_action")))));
        Facts facts = new Facts();
        facts.blockHere = null;
        PlanCursor start = PlanCursor.start(0, 0);

        PlanInterpreter.Transition first =
                PlanInterpreter.advance(plan, start, facts, new PlanInterpreter.ActionResult.None());
        var undecided = assertInstanceOf(PlanInterpreter.Event.Undecided.class, first.event());
        assertTrue(undecided.reason().contains("minecraft:stone"), undecided.reason());
        assertEquals(start, first.cursor(), "the cursor does not move past an undecidable node");
        assertTrue(first.cursor().selectedBranches().isEmpty(), "no branch was committed");

        facts.blockHere = false;
        var then = assertInstanceOf(
                PlanInterpreter.Event.EmitAction.class,
                PlanInterpreter.advance(plan, first.cursor(), facts, new PlanInterpreter.ActionResult.None())
                        .event());
        assertEquals("then_action", then.step().tool());

        facts.blockHere = true;
        var otherwise = assertInstanceOf(
                PlanInterpreter.Event.EmitAction.class,
                PlanInterpreter.advance(plan, first.cursor(), facts, new PlanInterpreter.ActionResult.None())
                        .event());
        assertEquals("else_action", otherwise.step().tool());
    }

    @Test
    void aRepeatWhoseUntilIsUnknownNeitherIteratesNorEnds() {
        PlanDefinition plan = new PlanDefinition(List.of(new PlanNode.Repeat(List.of(action("work")), blockAt(), 3)));
        Facts facts = new Facts();
        facts.blockHere = null;

        PlanCursor start = PlanCursor.start(0, 0);
        PlanInterpreter.Transition atEntry =
                PlanInterpreter.advance(plan, start, facts, new PlanInterpreter.ActionResult.None());
        assertInstanceOf(
                PlanInterpreter.Event.Undecided.class, atEntry.event(), "an unknown until does not start the loop");
        assertEquals(start, atEntry.cursor());

        facts.blockHere = false;
        PlanInterpreter.Transition entered =
                PlanInterpreter.advance(plan, atEntry.cursor(), facts, new PlanInterpreter.ActionResult.None());
        assertInstanceOf(PlanInterpreter.Event.EmitAction.class, entered.event());

        facts.blockHere = null;
        PlanInterpreter.Transition afterFirst =
                PlanInterpreter.advance(plan, entered.cursor(), facts, new PlanInterpreter.ActionResult.Succeeded());
        var undecided = assertInstanceOf(PlanInterpreter.Event.Undecided.class, afterFirst.event());
        assertTrue(undecided.path().endsWith("until"), undecided.path());
        assertEquals(
                entered.cursor().repeatIterations(),
                afterFirst.cursor().repeatIterations(),
                "an unknown condition spends no iteration");

        facts.blockHere = true;
        assertInstanceOf(
                PlanInterpreter.Event.Complete.class,
                PlanInterpreter.advance(plan, afterFirst.cursor(), facts, new PlanInterpreter.ActionResult.None())
                        .event());
    }

    @Test
    void aSkipGuardOnlySkipsOnConfirmedAbsence() {
        Facts facts = new Facts();
        facts.blockHere = null;
        JobQueue queue = queueWithSkip();
        Job job = queue.jobs().getFirst();
        assertEquals(Guard.Truth.UNKNOWN, job.guards().getFirst().evaluate(facts));
        assertFalse(QueuePolicy.guardConfirmedUnmet(job, facts), "unknown evidence leaves the step in place");

        facts.blockHere = false;
        assertTrue(QueuePolicy.guardConfirmedUnmet(job, facts), "a confirmed absence lets the step be skipped");
        facts.blockHere = true;
        assertFalse(QueuePolicy.guardConfirmedUnmet(job, facts));
    }

    @Test
    void anUnknownGuardIsNamedInTheJobReasonAndInThePlanRow() {
        Facts facts = new Facts();
        facts.blockHere = null;
        JobQueue queue = queueWithSkip();
        Job job = queue.jobs().getFirst();
        String reason = QueuePolicy.notReadyReason(queue, job, facts);
        assertTrue(reason.startsWith("cannot tell yet whether"), reason);
        assertTrue(reason.contains("minecraft:stone"), reason);
    }

    @Test
    void remainingIsALowerBoundWhileAConditionIsUnknown() {
        PlanDefinition plan = new PlanDefinition(List.of(
                action("first"), new PlanNode.Conditional(blockAt(), List.of(action("then_action")), List.of())));
        Facts facts = new Facts();
        facts.blockHere = null;
        PlanInterpreter.Remaining remaining = PlanInterpreter.remaining(plan, PlanCursor.start(0, 0), facts);
        assertFalse(remaining.exact(), "an unknown condition makes the count a lower bound");
        assertTrue(remaining.actions() >= 1, "the first action is still counted: " + remaining.actions());
    }

    @Test
    void anIdleRepairWaitsUntilTheNeedIsConfirmed() {
        Facts facts = new Facts();
        facts.canHarvest = null;
        JobQueue queue = new JobQueue(QueueLimits.DEFAULTS);
        queue.addDirect(new QueuedStep(
                "mine_blocks",
                new JsonObject(),
                null,
                List.of(new Guard.ToolAtLeast("minecraft:stone")),
                GuardAction.WAIT,
                FailurePolicy.ASK,
                1,
                List.of()));
        List<Job> jobs = List.copyOf(queue.jobs());
        assertTrue(
                IdleRepairPolicy.select(jobs, facts, Set.of("obtain_item"), Set.of(), ToolRepairRule.vanillaPickaxes())
                        .isEmpty(),
                "an unreadable tool check is not a missing tool");
        facts.canHarvest = false;
        assertTrue(
                IdleRepairPolicy.select(jobs, facts, Set.of("obtain_item"), Set.of(), ToolRepairRule.vanillaPickaxes())
                        .isPresent(),
                "a confirmed missing tool is repaired");
    }

    private static JobQueue queueWithSkip() {
        JobQueue queue = new JobQueue(QueueLimits.DEFAULTS);
        queue.addDirect(new QueuedStep(
                "mine_blocks",
                new JsonObject(),
                null,
                List.of(blockAt()),
                GuardAction.SKIP,
                FailurePolicy.ASK,
                1,
                List.of()));
        return queue;
    }

    private static PlanNode.Action action(String tool) {
        return new PlanNode.Action(
                new QueuedStep(tool, new JsonObject(), null, List.of(), GuardAction.WAIT, null, 1, List.of()));
    }

    @Test
    void anUnsurveyedBlockAreaIsUnknownAndNeverSatisfiesASkip() {
        Facts facts = new Facts();
        Guard.BlockWithin guard = new Guard.BlockWithin("minecraft:stone", 8, 4);
        assertEquals(Guard.Truth.UNKNOWN, guard.evaluate(facts));
        assertFalse(guard.holds(facts), "an unobserved area never claims the blocks are there");

        facts.blocksWithin = Boolean.TRUE;
        assertEquals(Guard.Truth.TRUE, guard.evaluate(facts));
        facts.blocksWithin = Boolean.FALSE;
        assertEquals(Guard.Truth.FALSE, guard.evaluate(facts));

        facts.blocksWithin = null;
        JobQueue queue = new JobQueue(QueueLimits.DEFAULTS);
        long id = queue.addDirect(new QueuedStep(
                        "mine", new JsonObject(), null, List.of(guard), GuardAction.SKIP, null, 1, List.of()))
                .id();
        Job job = queue.job(id).orElseThrow();
        assertFalse(QueuePolicy.guardConfirmedUnmet(job, facts));
        assertEquals("cannot tell yet whether " + guard.describe(), QueuePolicy.notReadyReason(queue, job, facts));

        facts.blocksWithin = Boolean.FALSE;
        assertTrue(QueuePolicy.guardConfirmedUnmet(job, facts));
    }
}
