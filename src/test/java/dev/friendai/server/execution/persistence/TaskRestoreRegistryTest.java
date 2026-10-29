package dev.friendai.server.execution.persistence;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import dev.friendai.agent.tools.Cost;
import dev.friendai.agent.tools.ToolGroup;
import dev.friendai.agent.tools.ToolMeta;
import dev.friendai.execution.queue.FailurePolicy;
import dev.friendai.execution.queue.GuardAction;
import dev.friendai.execution.queue.PlanDefinition;
import dev.friendai.execution.queue.PlanNode;
import dev.friendai.execution.queue.PlanRecord;
import dev.friendai.execution.queue.QueuedStep;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.server.execution.tools.TaskToolDefinition;
import java.util.List;
import java.util.Map;
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
