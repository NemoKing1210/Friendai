package dev.friendai.server.execution.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import dev.friendai.agent.llm.model.ToolCall;
import dev.friendai.agent.llm.model.ToolSpec;
import dev.friendai.agent.tools.ToolCatalog;
import dev.friendai.agent.tools.ToolMeta;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.execution.tool.Tool;
import dev.friendai.execution.tool.ToolRegistry;
import dev.friendai.execution.tool.ToolResult;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class QueueToolsTest {
    private static final Set<String> NAMES =
            Set.of("queue_add", "queue_show", "queue_edit", "plan_validate", "plan_help");

    @Test
    void registersTrustedQueueToolsUsingOnlyAlreadyRegisteredPlannableTaskDefinitions() {
        ToolRegistry<CompanionEntity> tools = new ToolRegistry<>();
        FeatureToolRegistry registry = new FeatureToolRegistry(tools, QueueToolsTest::taskTool);
        registry.registerTask(taskDefinition("look_around", true));
        registry.registerTask(taskDefinition("player_view", false));

        new QueueTools(new QueueToolAccessStub()).register(registry);

        assertTrue(names(tools.specs(AccessLevel.TRUSTED)).containsAll(NAMES));
        assertTrue(names(tools.specs(AccessLevel.OWNER)).containsAll(NAMES));
        assertTrue(tools.specs(AccessLevel.GUEST).isEmpty());
        assertEquals(
                NAMES,
                registry.catalogMetadata().stream()
                        .map(ToolMeta::name)
                        .filter(NAMES::contains)
                        .collect(Collectors.toSet()));
        registry.catalogMetadata().stream()
                .filter(meta -> NAMES.contains(meta.name()))
                .forEach(meta ->
                        assertEquals(ToolCatalog.defaults().meta(meta.name()).orElseThrow(), meta));

        JsonObject queueAdd = schema(tools, "queue_add");
        JsonObject steps = queueAdd.getAsJsonObject("properties").getAsJsonObject("steps");
        assertEquals(64, steps.get("maxItems").getAsInt());
        assertEquals(
                Set.of("look_around"),
                strings(steps.getAsJsonObject("items")
                        .getAsJsonObject("properties")
                        .getAsJsonObject("tool")
                        .getAsJsonArray("enum")));
        assertEquals(
                64,
                schema(tools, "plan_validate")
                        .getAsJsonObject("properties")
                        .getAsJsonObject("steps")
                        .get("maxItems")
                        .getAsInt());
    }

    @Test
    void planHelpRemainsAvailableAndDocumentsConditionalPlanGrammar() {
        ToolRegistry<CompanionEntity> tools = new ToolRegistry<>();
        FeatureToolRegistry registry = new FeatureToolRegistry(tools, QueueToolsTest::taskTool);
        registry.registerTask(taskDefinition("look_around", true));
        new QueueTools(new QueueToolAccessStub()).register(registry);

        ToolResult result = tools.bind(null)
                .execute(
                        new ToolCall("help", "plan_help", new JsonObject(), "{}", null),
                        new Caller(UUID.randomUUID(), "Tester", AccessLevel.TRUSTED));

        assertTrue(result.content().contains("max_iterations"));
        assertTrue(result.content().contains("all_of/any_of"));
    }

    private static TaskToolDefinition taskDefinition(String name, boolean plannable) {
        return new TaskToolDefinition(
                name,
                "Task " + name,
                new JsonObject(),
                (companion, args) -> null,
                plannable,
                ToolCatalog.defaults().meta(name).orElseThrow());
    }

    private static Tool<CompanionEntity> taskTool(TaskToolDefinition definition) {
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
                return ToolResult.ok("task");
            }
        };
    }

    private static Set<String> names(java.util.List<ToolSpec> specs) {
        return specs.stream().map(ToolSpec::name).collect(Collectors.toSet());
    }

    private static Set<String> strings(com.google.gson.JsonArray array) {
        return array.asList().stream().map(element -> element.getAsString()).collect(Collectors.toSet());
    }

    private static JsonObject schema(ToolRegistry<CompanionEntity> tools, String name) {
        return tools.specs().stream()
                .filter(spec -> spec.name().equals(name))
                .findFirst()
                .orElseThrow()
                .parametersSchema();
    }

    private static final class QueueToolAccessStub implements QueueToolAccess {
        @Override
        public Caller currentCaller(CompanionEntity companion, UUID principal) {
            throw new AssertionError("Registration must not resolve a current caller");
        }

        @Override
        public void linkPlan(CompanionEntity companion, long planId) {
            throw new AssertionError("Registration must not link a plan");
        }
    }
}
