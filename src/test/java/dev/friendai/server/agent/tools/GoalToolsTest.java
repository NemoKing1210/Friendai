package dev.friendai.server.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.friendai.agent.llm.model.ToolSpec;
import dev.friendai.agent.tools.ToolCatalog;
import dev.friendai.agent.tools.ToolMeta;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.ToolRegistry;
import dev.friendai.server.execution.tools.FeatureToolRegistry;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class GoalToolsTest {
    private static final Set<String> NAMES = Set.of("set_goal", "update_goal", "finish_goal");

    @Test
    void registersOwnerOnlyGoalToolsWithDefaultCatalogMetadataAndSchemas() {
        ToolRegistry<CompanionEntity> tools = new ToolRegistry<>();
        FeatureToolRegistry registry = new FeatureToolRegistry(tools, definition -> {
            throw new AssertionError("Goal tools are not task-backed");
        });

        new GoalTools(new GoalToolAccessStub()).register(registry);

        assertEquals(NAMES, names(tools.specs(AccessLevel.OWNER)));
        assertTrue(tools.specs(AccessLevel.TRUSTED).isEmpty());
        assertTrue(tools.specs(AccessLevel.GUEST).isEmpty());
        assertEquals(
                NAMES, registry.catalogMetadata().stream().map(ToolMeta::name).collect(Collectors.toSet()));
        registry.catalogMetadata()
                .forEach(meta ->
                        assertEquals(ToolCatalog.defaults().meta(meta.name()).orElseThrow(), meta));

        JsonObject setGoal = schema(tools, "set_goal");
        assertEquals(
                Set.of("text", "criteria", "background"),
                setGoal.getAsJsonObject("properties").keySet());
        assertEquals(Set.of("text"), strings(setGoal.getAsJsonArray("required")));
        assertEquals(
                6,
                setGoal.getAsJsonObject("properties")
                        .getAsJsonObject("criteria")
                        .get("maxItems")
                        .getAsInt());

        JsonObject updateGoal = schema(tools, "update_goal");
        assertEquals(
                Set.of("id", "text", "criteria", "status"),
                updateGoal.getAsJsonObject("properties").keySet());
        assertEquals(
                Set.of("active", "blocked"),
                strings(updateGoal
                        .getAsJsonObject("properties")
                        .getAsJsonObject("status")
                        .getAsJsonArray("enum")));
        assertEquals(Set.of("id", "done"), strings(schema(tools, "finish_goal").getAsJsonArray("required")));
    }

    @Test
    void criteriaKeepValidGuardsAndUseGuardDescriptionWhenTextIsMissing() {
        var parsed = GoalTools.parseCriterion(
                JsonParser.parseString("{\"type\":\"has_item\",\"item\":\"minecraft:oak_log\",\"count\":12}")
                        .getAsJsonObject());

        assertEquals("12 minecraft:oak_log", parsed.text());
        assertTrue(parsed.check().isPresent());

        var textOnly =
                GoalTools.parseCriterion(JsonParser.parseString("{\"text\":\"tell the owner\",\"type\":\"unknown\"}")
                        .getAsJsonObject());
        assertEquals("tell the owner", textOnly.text());
        assertTrue(textOnly.check().isEmpty());
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

    private static final class GoalToolAccessStub implements GoalToolAccess {
        @Override
        public dev.friendai.agent.goal.GoalService goals(net.minecraft.server.level.ServerLevel level) {
            throw new AssertionError("Registration must not access a world service");
        }

        @Override
        public void skillOutcome(
                net.minecraft.server.level.ServerLevel level,
                java.util.UUID owner,
                String source,
                dev.friendai.foundation.teaching.TeachingContext.OutcomeKind kind,
                String summary) {
            throw new AssertionError("Registration must not post an outcome");
        }
    }
}
