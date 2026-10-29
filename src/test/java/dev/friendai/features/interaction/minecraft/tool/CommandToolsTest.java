package dev.friendai.features.interaction.minecraft.tool;

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

class CommandToolsTest {
    private static final String DESCRIPTION =
            "Run one allowlisted gameplay command using only the online owner's normal command permissions. The "
                    + "companion is the command entity and @s; only teleport, time, weather, give-to-owner, "
                    + "effect-on-self, and enchant-owner forms are accepted. execute/function/schedule and other "
                    + "roots are denied. Feedback is bounded.";

    @Test
    void executeCommandContributionPreservesItsOwnerOnlyTaskContract() {
        ToolRegistry<CompanionEntity> tools = new ToolRegistry<>();
        FeatureToolRegistry contributions = new FeatureToolRegistry(tools, CommandToolsTest::taskTool);

        new CommandTools().register(contributions);

        TaskToolDefinition definition = contributions.taskDefinitions().get("execute_command");
        assertNotNull(definition);
        assertEquals("execute_command", definition.name());
        assertEquals(DESCRIPTION, definition.description());
        assertEquals(
                SchemaBuilder.object()
                        .string("command", "One command, with or without one leading slash")
                        .required("command")
                        .build(),
                definition.schema());
        assertNotNull(definition.factory());
        assertNotNull(definition.callerFactory());
        assertFalse(definition.plannable());
        assertEquals(AccessLevel.OWNER, definition.minAccess());
        assertEquals(TaskToolDefinition.QueueTraits.DEFAULT, definition.queueTraits());
        assertTrue(definition.restorable());
        assertTrue(definition.creativeSensitive());
        assertEquals(ToolCatalog.defaults().meta("execute_command").orElseThrow(), definition.catalogMeta());
        assertEquals(1, contributions.taskDefinitions().size());
        assertEquals(1, contributions.catalogMetadata().size());
        assertEquals(definition.catalogMeta(), contributions.catalogMetadata().getFirst());
        assertEquals("execute_command", tools.specs().getFirst().name());
        assertTrue(tools.specs(AccessLevel.OWNER).stream()
                .anyMatch(spec -> spec.name().equals("execute_command")));
        assertFalse(tools.specs(AccessLevel.TRUSTED).stream()
                .anyMatch(spec -> spec.name().equals("execute_command")));
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
