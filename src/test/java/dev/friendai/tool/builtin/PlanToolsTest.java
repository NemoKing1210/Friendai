package dev.friendai.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.friendai.agent.llm.model.ToolCall;
import dev.friendai.execution.queue.FailurePolicy;
import dev.friendai.execution.queue.GuardAction;
import dev.friendai.execution.queue.JobQueue;
import dev.friendai.execution.queue.PlanOptions;
import dev.friendai.execution.queue.QueueLimits;
import dev.friendai.execution.queue.QueuedStep;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.execution.tool.SchemaBuilder;
import dev.friendai.execution.tool.ToolRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlanToolsTest {
    @Test
    void planValidateReportsNestedErrorsWithoutQueueMutation() {
        TaskToolDef goTo = new TaskToolDef(
                "go_to",
                "Move to a destination.",
                SchemaBuilder.object().build(),
                (companion, args) -> null,
                true,
                AccessLevel.TRUSTED);
        ToolRegistry<dev.friendai.entity.CompanionEntity> registry = new ToolRegistry<>();
        QueueTools.register(registry, List.of(goTo));
        JobQueue queue = new JobQueue(QueueLimits.DEFAULTS);
        queue.addPlan(
                "existing",
                List.of(new QueuedStep(
                        "go_to", new JsonObject(), null, List.of(), GuardAction.WAIT, FailurePolicy.ASK, 1, List.of())),
                PlanOptions.DEFAULT);
        int jobsBefore = queue.jobCount(), plansBefore = queue.planCount();
        JsonObject args = JsonParser.parseString("""
                {"steps":[{"when":{"type":"fluid_ahead"},"then":[{"tool":"missing_tool"}]}]}
                """).getAsJsonObject();

        var result = registry.bind(null)
                .execute(
                        new ToolCall("dry-run", "plan_validate", args, args.toString(), null),
                        new Caller(null, "tester", AccessLevel.TRUSTED));

        assertTrue(result.error());
        assertTrue(result.content().contains("step 1 → then #1"), result.content());
        assertEquals(jobsBefore, queue.jobCount());
        assertEquals(plansBefore, queue.planCount());
    }

    @Test
    void planHelpDocumentsBoundedConditionalGrammar() {
        TaskToolDef goTo = new TaskToolDef(
                "go_to",
                "Move to a destination.",
                SchemaBuilder.object().build(),
                (companion, args) -> null,
                true,
                AccessLevel.TRUSTED);
        ToolRegistry<dev.friendai.entity.CompanionEntity> registry = new ToolRegistry<>();
        QueueTools.register(registry, List.of(goTo));
        var result = registry.bind(null)
                .execute(
                        new ToolCall("help", "plan_help", new JsonObject(), "{}", null),
                        new Caller(null, "tester", AccessLevel.TRUSTED));
        assertTrue(result.content().contains("max_iterations"));
        assertTrue(result.content().contains("all_of/any_of"));
    }
}
