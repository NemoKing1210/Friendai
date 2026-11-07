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
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The documented aliases must survive the queue and planner paths, not only a direct tool call. */
class QueueAliasEndToEndTest {
    @Test
    void aQueuedObtainItemsStepAcceptsTheDocumentedAlias() {
        TaskToolDefinition obtainItems = definitions().get("obtain_items");
        JsonArray steps = JsonParser.parseString(
                        "[{\"tool\":\"obtain_items\",\"args\":\"{\\\"item\\\":\\\"minecraft:stone\\\",\\\"count\\\":36}\"}]")
                .getAsJsonArray();

        QueueParser.PlanResult parsed = QueueParser.parsePlan(
                steps,
                Map.of("obtain_items", obtainItems.schema()),
                Map.of("obtain_items", obtainItems.normalizer()),
                Set.of("obtain_items"),
                12,
                8192,
                49152);

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
        JsonObject raw = JsonParser.parseString("{\"item\":\"minecraft:stone\",\"count\":36}")
                .getAsJsonObject();

        JsonObject normalized = QueueTools.normalizeEditArgs(obtainItems, raw.deepCopy());

        assertFalse(normalized.has("item"));
        assertEquals(1, normalized.getAsJsonArray("items").size());
        assertTrue(ArgValidator.validate(obtainItems.schema(), normalized).isEmpty());
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
