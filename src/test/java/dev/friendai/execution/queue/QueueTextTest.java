package dev.friendai.execution.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class QueueTextTest {
    private static final GuardFacts FACTS = new FakeGuardFacts(Map.of(), 10, 1, 0, Map.of(), "minecraft:overworld");

    private static QueuedStep step(String tool, String argsJson) {
        JsonObject args = JsonParser.parseString(argsJson).getAsJsonObject();
        return new QueuedStep(tool, args, null, null, null, null, 1, null);
    }

    @Test
    void emptyQueue() {
        JobQueue q = new JobQueue(QueueLimits.DEFAULTS);
        assertEquals("queue: empty", QueueText.show(q, FACTS, null, 20, false));
        assertEquals("Task queue: empty", QueueText.summary(q, FACTS, null));
    }

    @Test
    void clearedReportsWhatWasRemoved() {
        assertEquals("queue is already empty", QueueText.cleared(0, 0));
        assertEquals("cleared 1 plan and 3 jobs", QueueText.cleared(1, 3));
        assertEquals("cleared 2 plans and 1 job", QueueText.cleared(2, 1));
    }

    @Test
    void summaryNamesRunningAndBlockedWork() {
        JobQueue q = new JobQueue(QueueLimits.DEFAULTS);
        var steps = List.of(
                new QueuedStep("mine_blocks", new JsonObject(), null, List.of(), GuardAction.WAIT, null, 1, List.of()),
                new QueuedStep(
                        "place_block",
                        new JsonObject(),
                        null,
                        List.of(new Guard.HasItem("minecraft:iron_ingot", 3)),
                        GuardAction.WAIT,
                        null,
                        1,
                        List.of()));
        long active = q.addPlan("build", steps, PlanOptions.DEFAULT).jobIds().getFirst();
        q.markRunning(active);
        String text = QueueText.summary(q, FACTS, "mining stone");
        assertTrue(text.contains("Running: #" + active));
        assertTrue(text.contains("Blocked: #"));
        assertTrue(text.contains("needs 3 minecraft:iron_ingot"));
    }

    @Test
    void structuredInspectionShowsSelectedBranchPathAndRepeatProgress() {
        PlanDefinition definition = new PlanDefinition(List.of(new PlanNode.Conditional(
                new Guard.FluidAhead(),
                List.of(new PlanNode.Repeat(
                        List.of(new PlanNode.Action(new QueuedStep(
                                "go_to", new JsonObject(), null, List.of(), GuardAction.WAIT, null, 1, List.of()))),
                        new Guard.TimeElapsed(100),
                        10)),
                List.of())));
        JobQueue queue = new JobQueue(QueueLimits.DEFAULTS);
        long plan = queue.addStructuredPlan("travel", definition, PlanOptions.DEFAULT);
        queue.updatePlanCursor(
                plan,
                new PlanCursor(
                        List.of(new PlanCursor.Frame(List.of(), 0, "", "", false)),
                        Map.of("step 1", true),
                        Map.of("step 1 → then #1", 3),
                        0,
                        0,
                        "",
                        null));

        String shown = QueueText.show(queue, FACTS, null, 20, false);
        assertTrue(shown.contains("step 1 → then #1"), shown);
        assertTrue(shown.contains("selected then"), shown);
        assertTrue(shown.contains("3/10"), shown);
        assertTrue(shown.contains("tree: [if("), shown);
        assertTrue(shown.contains("repeat("), shown);
        assertTrue(shown.contains("go_to"), shown);
        var rows = dev.friendai.presentation.companion.QueueRows.build(queue, FACTS, null);
        assertTrue(
                rows.stream().anyMatch(row -> row.planId() == plan && row.note().contains("3/10")));
    }

    @Test
    void finishedSummaryCarriesEveryTerminalStepResult() {
        JobQueue q = new JobQueue(QueueLimits.DEFAULTS);
        var added =
                q.addPlan("tool set", List.of(step("give_items", "{}"), step("give_items", "{}")), PlanOptions.DEFAULT);
        q.finish(added.jobIds().get(0), "gave 1 minecraft:stone_pickaxe");
        q.finish(added.jobIds().get(1), "gave 1 minecraft:stone_axe");

        String summary = QueueText.finishedSummary(q, added.planId(), "conditional plan completed");

        assertEquals(
                "conditional plan completed; #" + added.jobIds().get(0)
                        + " give_items: gave 1 minecraft:stone_pickaxe; #"
                        + added.jobIds().get(1)
                        + " give_items: gave 1 minecraft:stone_axe",
                summary);
    }

    @Test
    void planResultsSkipsBlankResultsAndJobsThatDidNotFinish() {
        JobQueue q = new JobQueue(QueueLimits.DEFAULTS);
        var added = q.addPlan(
                "mixed",
                List.of(step("give_items", "{}"), step("give_items", "{}"), step("give_items", "{}")),
                PlanOptions.DEFAULT);
        q.finish(added.jobIds().get(0), "");
        q.finish(added.jobIds().get(1), "gave 1 minecraft:stone_axe");

        assertEquals(
                "#" + added.jobIds().get(1) + " give_items: gave 1 minecraft:stone_axe",
                QueueText.planResults(q, added.planId(), 600));
    }

    @Test
    void finishedSummaryWithoutResultsIsTheGivenSummary() {
        JobQueue q = new JobQueue(QueueLimits.DEFAULTS);
        var added = q.addPlan("stopped", List.of(step("give_items", "{}")), PlanOptions.DEFAULT);

        assertEquals("stopped by request", QueueText.finishedSummary(q, added.planId(), "stopped by request"));
    }

    @Test
    void cancelledJobKeepsItsCancellationTextOutOfThePlanResults() {
        JobQueue q = new JobQueue(QueueLimits.DEFAULTS);
        var added = q.addPlan("stopped", List.of(step("give_items", "{}")), PlanOptions.DEFAULT);
        q.cancel(added.jobIds().getFirst(), "stopped by request");

        // A cancelled job stores the reason as its "result"; only the DONE/FAILED filter keeps it out, so a step
        // the owner stopped must never be reported as something the plan produced.
        assertEquals("", QueueText.planResults(q, added.planId(), 600));
        assertEquals("stopped by request", QueueText.finishedSummary(q, added.planId(), "stopped by request"));
    }

    @Test
    void finishedSummaryDoesNotAppendTheSameDetailTwice() {
        JobQueue q = new JobQueue(QueueLimits.DEFAULTS);
        var added = q.addPlan("twice", List.of(step("give_items", "{}")), PlanOptions.DEFAULT);
        q.finish(added.jobIds().getFirst(), "gave 1 minecraft:stone_pickaxe");
        String once = QueueText.finishedSummary(q, added.planId(), "conditional plan completed");

        assertEquals(once, QueueText.finishedSummary(q, added.planId(), once));
    }

    @Test
    void planResultsIsBounded() {
        JobQueue q = new JobQueue(QueueLimits.DEFAULTS);
        List<QueuedStep> steps = new ArrayList<>();
        for (int i = 0; i < 10; i++) steps.add(step("give_items", "{}"));
        var added = q.addPlan("many", steps, PlanOptions.DEFAULT);
        for (long id : added.jobIds()) q.finish(id, "gave 1 minecraft:stone_pickaxe");

        String results = QueueText.planResults(q, added.planId(), 600);

        assertTrue(results.length() <= 600, results);
        assertTrue(results.endsWith("; …"), results);
    }

    @Test
    void anArchivedPlanIsShownByIdInsteadOfACount() {
        JobQueue q = new JobQueue(QueueLimits.DEFAULTS);
        var added = q.addPlan("tool set", List.of(step("give_items", "{}")), PlanOptions.DEFAULT);
        q.finish(added.jobIds().getFirst(), "gave 1 minecraft:stone_pickaxe");
        q.archivePlan(added.planId(), true, QueueText.finishedSummary(q, added.planId(), "conditional plan completed"));

        String shown = QueueText.show(q, FACTS, added.planId(), 20, true);

        assertTrue(shown.contains("plan #" + added.planId()), shown);
        assertTrue(shown.contains("success"), shown);
        assertTrue(shown.contains("gave 1 minecraft:stone_pickaxe"), shown);
        assertFalse(shown.contains("archived plan(s)"), shown);
    }

    @Test
    void anEmptyQueueShowsItsArchivesWithoutFlags() {
        JobQueue q = new JobQueue(QueueLimits.DEFAULTS);
        var added = q.addPlan("old work", List.of(step("give_items", "{}")), PlanOptions.DEFAULT);
        q.archivePlan(added.planId(), true, "finished");

        String shown = QueueText.show(q, FACTS, null, 20, false);

        assertTrue(shown.contains("plan #" + added.planId()), shown);
        assertTrue(shown.contains("old work"), shown);
        assertFalse(shown.contains("archived plan(s)"), shown);
    }

    @Test
    void aLiveQueueListsArchivesOnlyWhenAskedOrNamed() {
        JobQueue q = new JobQueue(QueueLimits.DEFAULTS);
        var archived = q.addPlan("old work", List.of(step("give_items", "{}")), PlanOptions.DEFAULT);
        q.archivePlan(archived.planId(), true, "finished");
        q.addPlan("live work", List.of(step("give_items", "{}")), PlanOptions.DEFAULT);

        assertFalse(QueueText.show(q, FACTS, null, 20, false).contains("plan #" + archived.planId()));
        assertTrue(QueueText.show(q, FACTS, null, 20, true).contains("plan #" + archived.planId()));
        assertTrue(QueueText.show(q, FACTS, archived.planId(), 20, false).contains("plan #" + archived.planId()));
    }

    @Test
    void aNamedArchiveThatNoArchiveHoldsRendersNothing() {
        JobQueue q = new JobQueue(QueueLimits.DEFAULTS);
        q.addPlan("live work", List.of(step("give_items", "{}")), PlanOptions.DEFAULT);

        String shown = QueueText.show(q, FACTS, 999L, 20, false);

        assertFalse(shown.contains("plan #999"), shown);
    }
}
