package dev.friendai.server.execution.persistence;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import dev.friendai.agent.tools.Cost;
import dev.friendai.agent.tools.ToolGroup;
import dev.friendai.agent.tools.ToolMeta;
import dev.friendai.execution.queue.FailurePolicy;
import dev.friendai.execution.queue.GuardAction;
import dev.friendai.execution.queue.Job;
import dev.friendai.execution.queue.JobQueue;
import dev.friendai.execution.queue.JobState;
import dev.friendai.execution.queue.PlanDefinition;
import dev.friendai.execution.queue.PlanNode;
import dev.friendai.execution.queue.PlanOptions;
import dev.friendai.execution.queue.PlanRecord;
import dev.friendai.execution.queue.QueueLimits;
import dev.friendai.execution.queue.QueuedStep;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.features.acquisition.minecraft.task.BatchObtainTask;
import dev.friendai.features.acquisition.minecraft.task.ObtainTask;
import dev.friendai.features.acquisition.minecraft.task.SearchResourceTask;
import dev.friendai.features.crafting.CraftPlanner;
import dev.friendai.features.interaction.minecraft.task.WaitTask;
import dev.friendai.server.execution.tools.TaskToolDefinition;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskState;
import dev.friendai.task.plan.PlanStepFactory;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TaskRestoreRegistryTest {
    @Test
    void nonRestorableTaskIsExcludedFromRestoreLookup() {
        TaskToolDefinition task = task("temporary", false, false);
        TaskRestoreRegistry registry = new TaskRestoreRegistry(
                Map.of(task.name(), task), Map.of(), Set.of(), () -> null, (owner, principal) -> null);

        assertFalse(registry.hasTaskTool(task.name()));
    }

    @Test
    void restorableTaskRemainsInRestoreLookup() {
        TaskToolDefinition task = task("saved", false, true);
        TaskRestoreRegistry registry = new TaskRestoreRegistry(
                Map.of(task.name(), task), Map.of(), Set.of(task.name()), () -> null, (owner, principal) -> null);

        assertTrue(registry.hasTaskTool(task.name()));
    }

    @Test
    void nonRestorableTaskCannotBeUsedByARestoredPlan() {
        TaskToolDefinition task = task("temporary", true, false);
        TaskRestoreRegistry registry = new TaskRestoreRegistry(
                Map.of(task.name(), task), Map.of(task.name(), task), Set.of(), () -> null, (owner, principal) -> null);
        PlanRecord plan = new PlanRecord(
                1,
                "goal",
                0,
                0,
                100,
                FailurePolicy.ASK,
                new PlanDefinition(List.of(new PlanNode.Action(new QueuedStep(
                        task.name(),
                        new JsonObject(),
                        null,
                        List.of(),
                        GuardAction.WAIT,
                        FailurePolicy.ASK,
                        1,
                        List.of())))),
                null);

        assertFalse(registry.hasPlanTools(plan));
    }

    @Test
    void aRestoredPlanCanEmitAnyPlannableToolAnEditAddsLater() {
        TaskToolDefinition wait = task("wait", true, true);
        TaskToolDefinition stay = task("stay", true, true);
        TaskRestoreRegistry registry = new TaskRestoreRegistry(
                Map.of(wait.name(), wait, stay.name(), stay),
                Map.of(wait.name(), wait, stay.name(), stay),
                Set.of(wait.name(), stay.name()),
                () -> null,
                (owner, principal) -> null);
        PlanDefinition definition = new PlanDefinition(List.of(new PlanNode.Action(new QueuedStep(
                wait.name(), new JsonObject(), null, List.of(), GuardAction.WAIT, FailurePolicy.ASK, 1, List.of()))));
        JobQueue queue = new JobQueue(QueueLimits.DEFAULTS);
        long planId = queue.addStructuredPlan("restored", definition, PlanOptions.DEFAULT);

        Map<String, PlanStepFactory> factories = registry.planFactories(queue).get(planId);

        assertTrue(
                factories.keySet().containsAll(Set.of(wait.name(), stay.name())),
                "a plan restored with only one tool in its tree still carries a factory for a tool an edit adds: "
                        + factories.keySet());
    }

    @Test
    void aContributedCheckpointRestoresWithoutEditingOrchestration() {
        Task resumed = new WaitTask(7);
        TaskToolDefinition contributed = task("saved", false, true)
                .withCheckpoint(
                        "custom_checkpoint",
                        (state, context) -> state.data().equals("payload") ? Optional.of(resumed) : Optional.empty());
        TaskRestoreRegistry registry = new TaskRestoreRegistry(
                Map.of(contributed.name(), contributed),
                Map.of(),
                Set.of(contributed.name()),
                () -> null,
                (owner, principal) -> null);
        TaskContext context = new TaskContext(null, null, null, null, null);

        assertSame(
                resumed,
                registry.restore(new TaskState("custom_checkpoint", "payload"), context)
                        .orElseThrow(),
                "the contributed decoder restores its own key");
        assertTrue(
                registry.restore(new TaskState("custom_checkpoint", "corrupt"), context)
                        .isEmpty(),
                "malformed checkpoint data is rejected by the contributed decoder");
        assertTrue(registry.restore(new TaskState("unknown_type", "payload"), context)
                .isEmpty());
    }

    @Test
    void aCheckpointRestorerRequiresARestorableTask() {
        assertThrows(
                IllegalArgumentException.class,
                () -> task("temporary", false, false).withCheckpoint("unused", (state, context) -> Optional.empty()),
                "a decoder without the restorable opt-in must fail at registration");
    }

    @Test
    void duplicateCheckpointTypesAreRejected() {
        TaskToolDefinition first =
                task("first", false, true).withCheckpoint("same", (state, context) -> Optional.empty());
        TaskToolDefinition second =
                task("second", false, true).withCheckpoint("same", (state, context) -> Optional.empty());

        assertThrows(
                IllegalArgumentException.class,
                () -> new TaskRestoreRegistry(
                        Map.of(first.name(), first, second.name(), second),
                        Map.of(),
                        Set.of(first.name(), second.name()),
                        () -> null,
                        (owner, principal) -> null));
    }

    @Test
    void anOmittedExploreAndAnExplicitTrueAreTheSameOption() {
        TaskRestoreRegistry registry = registry();

        assertTrue(
                registry.interruptedSearch(job("obtain_item", new JsonObject()), null),
                "omitted explore uses the effective default");
        assertTrue(
                registry.interruptedSearch(job("obtain_item", args(true)), null),
                "an explicit true must behave like the omitted default");
        assertFalse(
                registry.interruptedSearch(job("obtain_item", args(false)), null),
                "explore=false keeps the acquisition out of the search path");
    }

    @Test
    void aSavedLocalOrStationPhaseIsRebuiltEvenWhenExploreIsOn() {
        TaskRestoreRegistry registry = registry();

        assertFalse(
                registry.interruptedSearch(
                        job("obtain_item", new JsonObject()), obtainCheckpoint(ObtainTask.Phase.LOCAL)),
                "a restored local step replans from the live bag");
        assertFalse(
                registry.interruptedSearch(job("obtain_item", args(true)), obtainCheckpoint(ObtainTask.Phase.STATION)),
                "a station step is not a search, whatever the job option says");
    }

    @Test
    void aSavedNestedSearchIsNeverResumedUnderAFreshBudget() {
        TaskRestoreRegistry registry = registry();

        assertTrue(
                registry.interruptedSearch(
                        job("obtain_item", new JsonObject()), obtainCheckpoint(ObtainTask.Phase.NESTED_SEARCH)),
                "a running search is cancelled, not replanned");
        assertTrue(
                registry.interruptedSearch(
                        job("obtain_items", new JsonObject()), batchCheckpoint(BatchObtainTask.Phase.NESTED_SEARCH)),
                "a batch that was searching is cancelled the same way");
        assertFalse(
                registry.interruptedSearch(
                        job("obtain_items", args(true)), batchCheckpoint(BatchObtainTask.Phase.GATHER)),
                "a batch that was merely gathering is rebuilt from the live bag");
    }

    @Test
    void aLegacyAcquisitionSaveIsReadFromItsChildCheckpoint() {
        TaskRestoreRegistry registry = registry();

        assertTrue(
                registry.interruptedSearch(
                        job("obtain_item", new JsonObject()),
                        new TaskState(SearchResourceTask.TYPE, "legacy child progress")),
                "a legacy save whose child was a search is still cancelled");
        assertFalse(
                registry.interruptedSearch(job("obtain_item", args(true)), new TaskState("obtain_child", "progress")),
                "a legacy save whose child was any other step is rebuilt instead of cancelled");
    }

    @Test
    void anUnreadableAcquisitionCheckpointIsCancelledRatherThanGuessed() {
        TaskRestoreRegistry registry = registry();

        assertTrue(
                registry.interruptedSearch(job("obtain_item", new JsonObject()), new TaskState(ObtainTask.TYPE, "{}")),
                "a payload that no longer decodes cannot prove the phase");
        assertTrue(
                registry.interruptedSearch(job("obtain_items", args(false)), new TaskState(BatchObtainTask.TYPE, "{}")),
                "a batch payload that no longer decodes cannot prove the phase");
    }

    @Test
    void onlyAnAcquisitionSaveIsTakenAsItsOwnBoundary() {
        TaskRestoreRegistry registry = registry();

        assertTrue(registry.savesOwnBoundary(ObtainTask.TYPE));
        assertTrue(registry.savesOwnBoundary(BatchObtainTask.TYPE));
        assertFalse(registry.savesOwnBoundary(SearchResourceTask.TYPE));
        assertFalse(registry.savesOwnBoundary("obtain_child"));
    }

    private static TaskRestoreRegistry registry() {
        return new TaskRestoreRegistry(Map.of(), Map.of(), Set.of(), () -> null, (owner, principal) -> null);
    }

    private static Job job(String tool, JsonObject args) {
        return new Job(
                1,
                0,
                tool,
                args,
                0,
                false,
                0,
                JobState.RUNNING,
                List.of(),
                GuardAction.WAIT,
                FailurePolicy.ASK,
                1,
                0,
                List.of(),
                "",
                0);
    }

    private static JsonObject args(boolean explore) {
        JsonObject args = new JsonObject();
        args.addProperty("explore", explore);
        return args;
    }

    private static TaskState obtainCheckpoint(ObtainTask.Phase phase) {
        return new TaskState(
                ObtainTask.TYPE,
                new ObtainTask.Saved(1, phase, "minecraft:oak_log", 4, true, true, 10, 600, 64, 128, 600, "local", null)
                        .encode());
    }

    private static TaskState batchCheckpoint(BatchObtainTask.Phase phase) {
        return new TaskState(
                BatchObtainTask.TYPE,
                new BatchObtainTask.Saved(
                                1,
                                phase,
                                List.of(new CraftPlanner.Target("minecraft:stone_pickaxe", 1)),
                                true,
                                true,
                                32,
                                12_000,
                                32,
                                96,
                                400,
                                "local",
                                null)
                        .encode());
    }

    private static TaskToolDefinition task(String name, boolean plannable, boolean restorable) {
        return new TaskToolDefinition(
                name,
                name,
                new JsonObject(),
                (companion, args) -> null,
                null,
                plannable,
                AccessLevel.TRUSTED,
                TaskToolDefinition.QueueTraits.DEFAULT,
                restorable,
                false,
                new ToolMeta(name, ToolGroup.CORE, false, "", List.of(), Cost.LOW));
    }
}
