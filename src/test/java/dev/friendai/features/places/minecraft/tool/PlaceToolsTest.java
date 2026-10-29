package dev.friendai.features.places.minecraft.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import dev.friendai.agent.tools.ToolCatalog;
import dev.friendai.agent.tools.ToolGroup;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Tool;
import dev.friendai.execution.tool.ToolRegistry;
import dev.friendai.execution.tool.ToolResult;
import dev.friendai.server.execution.tools.FeatureToolRegistry;
import dev.friendai.server.execution.tools.TaskToolDefinition;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class PlaceToolsTest {
    private static final Set<String> PLACE_TOOLS =
            Set.of("remember_place", "find_place", "go_to_place", "rename_place", "forget_place");
    private static final Set<String> TRUSTED_TOOLS = Set.of("remember_place", "find_place", "go_to_place");

    @Test
    void contributesEveryPlaceToolWithMatchingCatalogMetadataAndAccess() {
        ToolRegistry<CompanionEntity> tools = new ToolRegistry<>();
        FeatureToolRegistry contributions = new FeatureToolRegistry(tools, PlaceToolsTest::taskTool);
        new PlaceTools().register(contributions);

        assertEquals(PLACE_TOOLS, toolNames(tools.specs()));
        assertEquals(TRUSTED_TOOLS, toolNames(tools.specs(AccessLevel.TRUSTED)));
        assertEquals(PLACE_TOOLS, toolNames(tools.specs(AccessLevel.OWNER)));
        assertEquals(Set.of(), toolNames(tools.specs(AccessLevel.GUEST)));
        assertEquals(
                PLACE_TOOLS,
                contributions.catalogMetadata().stream()
                        .map(meta -> meta.name())
                        .collect(Collectors.toSet()));

        ToolCatalog defaults = ToolCatalog.defaults();
        contributions.catalogMetadata().forEach(metadata -> {
            assertEquals(defaults.meta(metadata.name()).orElseThrow(), metadata);
            assertEquals(metadata.name().equals("find_place") ? ToolGroup.CORE : ToolGroup.PLACE, metadata.group());
        });
    }

    @Test
    void goToPlaceKeepsItsPlannableRestorableTaskContract() {
        ToolRegistry<CompanionEntity> tools = new ToolRegistry<>();
        FeatureToolRegistry contributions = new FeatureToolRegistry(tools, PlaceToolsTest::taskTool);
        new PlaceTools().register(contributions);

        TaskToolDefinition definition = contributions.taskDefinitions().get("go_to_place");

        assertEquals(Set.of("go_to_place"), contributions.taskDefinitions().keySet());
        assertEquals(Set.of("go_to_place"), contributions.restorableTaskNames());
        assertTrue(definition.plannable());
        assertTrue(definition.restorable());
        assertEquals(AccessLevel.TRUSTED, definition.minAccess());
        assertEquals(new TaskToolDefinition.QueueTraits(true, null), definition.queueTraits());
        assertEquals(ToolGroup.PLACE, definition.catalogMeta().group());
    }

    private static Set<String> toolNames(java.util.List<dev.friendai.agent.llm.model.ToolSpec> specs) {
        return specs.stream().map(spec -> spec.name()).collect(Collectors.toSet());
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
                return ToolResult.ok(definition.name());
            }
        };
    }
}
