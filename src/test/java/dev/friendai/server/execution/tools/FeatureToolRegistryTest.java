package dev.friendai.server.execution.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import dev.friendai.agent.tools.ToolCatalog;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Tool;
import dev.friendai.execution.tool.ToolRegistry;
import dev.friendai.execution.tool.ToolResult;
import dev.friendai.features.navigation.minecraft.tool.MovementTools;
import dev.friendai.features.navigation.minecraft.tool.RelativeMovementTools;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FeatureToolRegistryTest {
    @Test
    void navigationContributionsKeepRuntimeTaskAndCatalogNamesAligned() {
        ToolRegistry<CompanionEntity> tools = new ToolRegistry<>();
        FeatureToolRegistry registry = new FeatureToolRegistry(tools, FeatureToolRegistryTest::taskTool);
        new MovementTools().register(registry);
        new RelativeMovementTools().register(registry);

        Set<String> expectedNames =
                Set.of("follow_player", "stay", "go_to", "jump", "set_pace", "step", "back_away", "approach", "circle");
        Set<String> runtimeNames =
                tools.specs().stream().map(spec -> spec.name()).collect(java.util.stream.Collectors.toSet());
        Set<String> metadataNames = registry.catalogMetadata().stream()
                .map(meta -> meta.name())
                .collect(java.util.stream.Collectors.toSet());

        assertEquals(expectedNames, runtimeNames);
        assertEquals(expectedNames, metadataNames);
        assertEquals(
                expectedNames.stream()
                        .filter(name -> !name.equals("set_pace"))
                        .collect(java.util.stream.Collectors.toSet()),
                registry.taskDefinitions().keySet());
        assertEquals(registry.taskDefinitions().keySet(), registry.restorableTaskNames());
        assertEquals(
                Set.of("go_to", "jump", "step", "back_away", "approach", "circle"),
                registry.taskDefinitions().values().stream()
                        .filter(TaskToolDefinition::plannable)
                        .map(TaskToolDefinition::name)
                        .collect(java.util.stream.Collectors.toSet()));

        ToolCatalog catalog = ToolCatalog.defaults();
        for (var metadata : registry.catalogMetadata()) {
            assertEquals(catalog.meta(metadata.name()).orElseThrow(), metadata);
        }
    }

    @Test
    void duplicateFeatureTaskNameIsRejected() {
        FeatureToolRegistry registry = new FeatureToolRegistry(new ToolRegistry<>(), FeatureToolRegistryTest::taskTool);
        MovementTools contributor = new MovementTools();
        contributor.register(registry);

        IllegalArgumentException duplicate = assertThrows(IllegalArgumentException.class, () -> {
            TaskToolDefinition definition = registry.taskDefinitions().get("go_to");
            registry.registerTask(definition);
        });

        assertEquals("Tool already registered: go_to", duplicate.getMessage());
        assertTrue(registry.taskDefinitions().containsKey("go_to"));
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
