package dev.friendai.server.execution.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.queue.PlanNode;
import dev.friendai.execution.queue.QueuedStep;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.ArgValidator;
import dev.friendai.execution.tool.QueueParser;
import dev.friendai.execution.tool.Tool;
import dev.friendai.execution.tool.ToolRegistry;
import dev.friendai.execution.tool.ToolResult;
import dev.friendai.features.acquisition.minecraft.tool.ObtainTools;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The documented aliases must survive the queue and planner paths, not only a direct tool call. The tests drive the
 * production parse and update operations, which take the alias map from the tool definitions rather than a caller.
 */
class QueueAliasEndToEndTest {
    @Test
    void aQueuedObtainItemsStepAcceptsTheDocumentedAlias() {
        Map<String, TaskToolDefinition> definitions = definitions();
        JsonArray steps = JsonParser.parseString(
                        "[{\"tool\":\"obtain_items\",\"args\":\"{\\\"item\\\":\\\"minecraft:stone\\\",\\\"count\\\":36}\"}]")
                .getAsJsonArray();

        QueueParser.PlanResult parsed = QueueTools.parsePlan(steps, definitions, definitions.keySet(), 12);

        assertTrue(parsed.ok(), () -> String.join("; ", parsed.errors()));
        QueuedStep step = ((PlanNode.Action) parsed.plan().nodes().get(0)).step();
        assertFalse(step.args().has("item"));
        assertEquals(1, step.args().getAsJsonArray("items").size());
        JsonObject entry = step.args().getAsJsonArray("items").get(0).getAsJsonObject();
        assertEquals("minecraft:stone", entry.get("item").getAsString());
        assertEquals(36, entry.get("count").getAsInt());
    }

    @Test
    void queueEditUpdateNormalizesTheSameAliasBeforeValidation() {
        TaskToolDefinition obtainItems = definitions().get("obtain_items");

        JsonObject normalized = QueueTools.updateArgs(obtainItems, "{\"item\":\"minecraft:stone\",\"count\":36}");

        assertFalse(normalized.has("item"));
        assertEquals(1, normalized.getAsJsonArray("items").size());
        assertTrue(ArgValidator.validate(obtainItems.schema(), normalized).isEmpty());
    }

    @Test
    void aBrokenEditNormalizerLeavesTheArgumentsForValidationToReject() {
        TaskToolDefinition obtainItems = definitions().get("obtain_items");
        String raw = "{\"item\":\"minecraft:stone\",\"count\":36}";
        JsonObject expected = JsonParser.parseString(raw).getAsJsonObject();

        TaskToolDefinition thrown = obtainItems.withNormalizer(args -> {
            throw new IllegalStateException("broken normalizer");
        });
        JsonObject keptAfterThrow = QueueTools.updateArgs(thrown, raw);
        assertEquals(expected, keptAfterThrow);
        assertFalse(ArgValidator.validate(thrown.schema(), keptAfterThrow).isEmpty());

        TaskToolDefinition empty = obtainItems.withNormalizer(args -> null);
        JsonObject keptAfterEmpty = QueueTools.updateArgs(empty, raw);
        assertEquals(expected, keptAfterEmpty);
        assertFalse(ArgValidator.validate(empty.schema(), keptAfterEmpty).isEmpty());
    }

    private static Map<String, TaskToolDefinition> definitions() {
        FeatureToolRegistry registry = new FeatureToolRegistry(new ToolRegistry<>(), QueueAliasEndToEndTest::stubTool);
        new ObtainTools(new MinecraftObtainSubtaskFactory()).register(registry);
        return registry.taskDefinitions();
    }

    private static Tool<CompanionEntity> stubTool(TaskToolDefinition definition) {
        return new Tool<>() {
            @Override
            public String name() {
                return definition.name();
            }

            @Override
            public String description() {
                return definition.description();
            }

            @Override
            public JsonObject schema() {
                return definition.schema();
            }

            @Override
            public AccessLevel minAccess() {
                return definition.minAccess();
            }

            @Override
            public ToolResult execute(CompanionEntity companion, JsonObject args) {
                return ToolResult.ok(definition.name());
            }
        };
    }
}
