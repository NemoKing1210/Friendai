package dev.friendai.features.creative.minecraft.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import dev.friendai.agent.tools.ToolCatalog;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.SchemaBuilder;
import dev.friendai.execution.tool.Tool;
import dev.friendai.execution.tool.ToolRegistry;
import dev.friendai.execution.tool.ToolResult;
import dev.friendai.server.execution.tools.FeatureToolRegistry;
import dev.friendai.server.execution.tools.TaskToolDefinition;
import org.junit.jupiter.api.Test;

class CreativeToolsContributorTest {
    private static final String DESCRIPTION =
            "Create registered items with their default components. Delivered items remain after creative mode is "
                    + "turned off. The destination is your inventory unless owner is requested; the owner must be "
                    + "online within 16.0 blocks in the same dimension.";

    @Test
    void createItemContributionPreservesItsOwnerOnlyTaskContract() {
        ToolRegistry<CompanionEntity> tools = new ToolRegistry<>();
        FeatureToolRegistry contributions = new FeatureToolRegistry(tools, CreativeToolsContributorTest::taskTool);

        new CreativeTools().register(contributions);

        TaskToolDefinition definition = contributions.taskDefinitions().get("create_item");
        assertNotNull(definition);
        assertEquals("create_item", definition.name());
        assertEquals(DESCRIPTION, definition.description());
        assertEquals(
                SchemaBuilder.object()
                        .string("item", "Registered item id, for example minecraft:iron_ingot")
                        .integer(
                                "count",
                                "Exact positive number of items to create; delivery is bounded by inventory space")
                        .enumeration(
                                "destination",
                                "Where to deliver items: self (default) or owner nearby",
                                "self",
                                "owner")
                        .required("item", "count")
                        .build(),
                definition.schema());
        assertTaskTraits(definition);
        assertEquals(ToolCatalog.defaults().meta("create_item").orElseThrow(), definition.catalogMeta());
        assertEquals(1, contributions.taskDefinitions().size());
        assertEquals(1, contributions.catalogMetadata().size());
        assertEquals(definition.catalogMeta(), contributions.catalogMetadata().getFirst());
        assertEquals("create_item", tools.specs().getFirst().name());
        assertTrue(tools.specs(AccessLevel.OWNER).stream()
                .anyMatch(spec -> spec.name().equals("create_item")));
        assertFalse(tools.specs(AccessLevel.TRUSTED).stream()
                .anyMatch(spec -> spec.name().equals("create_item")));
    }

    private static void assertTaskTraits(TaskToolDefinition definition) {
        assertNotNull(definition.factory());
        assertNotNull(definition.callerFactory());
        assertFalse(definition.plannable());
        assertEquals(AccessLevel.OWNER, definition.minAccess());
        assertEquals(TaskToolDefinition.QueueTraits.DEFAULT, definition.queueTraits());
        assertTrue(definition.restorable());
        assertTrue(definition.creativeSensitive());
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
