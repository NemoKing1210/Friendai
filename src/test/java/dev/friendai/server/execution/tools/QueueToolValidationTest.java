package dev.friendai.server.execution.tools;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.friendai.agent.llm.model.ToolCall;
import dev.friendai.agent.tools.Cost;
import dev.friendai.agent.tools.ToolGroup;
import dev.friendai.agent.tools.ToolMeta;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.execution.tool.SchemaBuilder;
import dev.friendai.execution.tool.ToolRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

class QueueToolValidationTest {
    @Test
    void reportsNestedErrorsForConditionalPlans() {
        JsonObject args = JsonParser.parseString("""
                {"steps":[{"when":{"type":"fluid_ahead"},"then":[{"tool":"missing_tool"}]}]}
                """).getAsJsonObject();

        var result = QueueTools.validatePlan(
                args.getAsJsonArray("steps"), new Caller(null, "tester", AccessLevel.TRUSTED), taskDefinitions(), 64);

        assertTrue(result.error());
        assertTrue(result.content().contains("step 1 → then #1"), result.content());
    }

    @Test
    void planHelpDocumentsBoundedConditionalGrammar() {
        var result = registry()
                .bind(null)
                .execute(
                        new ToolCall("help", "plan_help", new JsonObject(), "{}", null),
                        new Caller(null, "tester", AccessLevel.TRUSTED));
        assertTrue(result.content().contains("max_iterations"));
        assertTrue(result.content().contains("all_of/any_of"));
    }

    private static ToolRegistry<dev.friendai.entity.CompanionEntity> registry() {
        ToolRegistry<dev.friendai.entity.CompanionEntity> tools = new ToolRegistry<>();
        QueueToolAccess access = new QueueToolAccess() {
            @Override
            public Caller currentCaller(dev.friendai.entity.CompanionEntity companion, java.util.UUID principal) {
                return new Caller(principal, "", AccessLevel.GUEST);
            }

            @Override
            public void linkPlan(dev.friendai.entity.CompanionEntity companion, long planId) {}
        };
        FeatureToolRegistry contributions =
                new FeatureToolRegistry(tools, definition -> TaskToolRuntime.tool(definition, access));
        contributions.registerTask(new TaskToolDefinition(
                "go_to",
                "Move to a destination.",
                SchemaBuilder.object().build(),
                (companion, args) -> null,
                true,
                new ToolMeta("go_to", ToolGroup.MOVEMENT, false, "", List.of(), Cost.MEDIUM)));
        new QueueTools(access).register(contributions);
        return tools;
    }

    private static java.util.Map<String, TaskToolDefinition> taskDefinitions() {
        return java.util.Map.of(
                "go_to",
                new TaskToolDefinition(
                        "go_to",
                        "Move to a destination.",
                        SchemaBuilder.object().build(),
                        (companion, args) -> null,
                        true,
                        new ToolMeta("go_to", ToolGroup.MOVEMENT, false, "", List.of(), Cost.MEDIUM)));
    }
}
