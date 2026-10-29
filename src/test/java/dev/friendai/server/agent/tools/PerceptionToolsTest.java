package dev.friendai.server.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.friendai.agent.llm.model.ToolCall;
import dev.friendai.agent.llm.model.ToolSpec;
import dev.friendai.agent.tools.ToolCatalog;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.execution.tool.ToolRegistry;
import dev.friendai.execution.tool.ToolResult;
import dev.friendai.server.execution.tools.FeatureToolRegistry;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class PerceptionToolsTest {
    private static final Set<String> TOOL_NAMES = Set.of("look_around", "player_view", "survey_area");

    @Test
    void contributesOnlyPerceptionToolsWithOriginalAccessAndMetadata() {
        ToolRegistry<CompanionEntity> tools = new ToolRegistry<>();
        FeatureToolRegistry contributions = new FeatureToolRegistry(tools, definition -> {
            throw new AssertionError("Perception tools are not task-backed");
        });

        new PerceptionTools(() -> null, () -> null, companion -> "").register(contributions);

        assertEquals(TOOL_NAMES, names(tools.specs()));
        assertEquals(Set.of("look_around"), names(tools.specs(AccessLevel.GUEST)));
        assertEquals(TOOL_NAMES, names(tools.specs(AccessLevel.TRUSTED)));
        assertEquals(TOOL_NAMES, names(tools.specs(AccessLevel.OWNER)));
        assertEquals(
                List.of("look_around", "player_view", "survey_area"),
                contributions.catalogMetadata().stream()
                        .map(meta -> meta.name())
                        .toList());
        contributions
                .catalogMetadata()
                .forEach(metadata -> assertEquals(
                        ToolCatalog.defaults().meta(metadata.name()).orElseThrow(), metadata));
        assertTrue(contributions.taskDefinitions().isEmpty());
        assertTrue(contributions.restorableTaskNames().isEmpty());
    }

    @Test
    void preservesDescriptionsAndSchemas() {
        ToolRegistry<CompanionEntity> tools = registry();

        assertEquals(
                "Describe the surroundings in detail.",
                spec(tools, "look_around").description());
        assertEquals(
                "Where the player who is talking to you stands and what they are looking at or standing on, "
                        + "right now. Use it when they say 'this block', 'the one I'm looking at' or 'where I stand', "
                        + "especially after they may have moved.",
                spec(tools, "player_view").description());
        assertEquals(
                "Draw a top-down map of the area at your eye level. Legend: @ you, O the speaker, Z hostile, "
                        + "# solid, ~ water, ^ lava or fire, . air, ? unloaded chunk. Use it to find a way around "
                        + "walls or to see where a threat stands.",
                spec(tools, "survey_area").description());
        assertEquals(
                JsonParser.parseString("{\"type\":\"object\",\"properties\":{}}"),
                spec(tools, "look_around").parametersSchema());
        assertEquals(
                JsonParser.parseString("{\"type\":\"object\",\"properties\":{}}"),
                spec(tools, "player_view").parametersSchema());
        assertEquals(
                JsonParser.parseString("{\"type\":\"object\",\"properties\":{\"radius\":{\"type\":\"integer\","
                        + "\"description\":\"Map radius in blocks\",\"minimum\":3,\"maximum\":16}}}"),
                spec(tools, "survey_area").parametersSchema());
    }

    @Test
    void lookAroundReportsUnavailablePerceptionWhenContextIsMissing() {
        ToolRegistry<CompanionEntity> tools = registry();
        JsonObject args = new JsonObject();

        ToolResult result = tools.bind(null)
                .execute(
                        new ToolCall("call-1", "look_around", args, args.toString(), null),
                        new Caller(UUID.randomUUID(), "Owner", AccessLevel.GUEST));

        assertTrue(result.error());
        assertEquals("Perception is unavailable", result.content());
    }

    private static ToolRegistry<CompanionEntity> registry() {
        ToolRegistry<CompanionEntity> tools = new ToolRegistry<>();
        new PerceptionTools(() -> null, () -> null, companion -> "")
                .register(new FeatureToolRegistry(tools, definition -> {
                    throw new AssertionError("Perception tools are not task-backed");
                }));
        return tools;
    }

    private static ToolSpec spec(ToolRegistry<CompanionEntity> tools, String name) {
        return tools.specs().stream()
                .filter(spec -> spec.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static Set<String> names(List<dev.friendai.agent.llm.model.ToolSpec> specs) {
        return specs.stream().map(spec -> spec.name()).collect(Collectors.toSet());
    }
}
