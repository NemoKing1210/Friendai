package dev.friendai.features.mining.minecraft.tool;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Tool;
import dev.friendai.execution.tool.ToolRegistry;
import dev.friendai.execution.tool.ToolResult;
import dev.friendai.server.execution.tools.FeatureToolRegistry;
import dev.friendai.server.execution.tools.TaskToolDefinition;
import org.junit.jupiter.api.Test;

class GatheringToolsTest {
    @Test
    void mineBlocksSchemaCarriesTheNewOptionalFields() {
        JsonObject properties = definition("mine_blocks").schema().getAsJsonObject("properties");

        assertTrue(properties.has("radius"));
        assertTrue(properties.has("exclude"));
        assertTrue(properties.has("until_item"));
        assertTrue(properties.has("until_count"));
        assertTrue(properties.has("require_tool"));
    }

    @Test
    void mineBlocksRejectsAnUnknownArgumentBeforeTouchingTheWorld() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> definition("mine_blocks").factory().create(null, args("""
                        {"block":"minecraft:stone","count":1,"radius":8,"wrong":true}
                        """)));

        assertTrue(error.getMessage().contains("wrong"));
        assertTrue(error.getMessage().contains("radius"));
    }

    @Test
    void untilRequiresBothFieldsAndCollection() {
        assertThrows(
                IllegalArgumentException.class,
                () -> definition("mine_blocks").factory().create(null, args("""
                                {"block":"minecraft:stone","count":1,"until_item":"minecraft:raw_iron"}
                                """)));
        assertThrows(
                IllegalArgumentException.class,
                () -> definition("mine_blocks").factory().create(null, args("""
                                {"block":"minecraft:stone","count":1,"collect":false,
                                 "until_item":"minecraft:raw_iron","until_count":3}
                                """)));
    }

    @Test
    void mineBlockAtRejectsTheFieldsItDoesNotUse() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> definition("mine_block_at").factory().create(null, args("""
                                {"x":1,"y":2,"z":3,"radius":4}
                                """)));

        assertTrue(error.getMessage().contains("radius"));
    }

    private static TaskToolDefinition definition(String name) {
        ToolRegistry<CompanionEntity> tools = new ToolRegistry<>();
        FeatureToolRegistry contributions = new FeatureToolRegistry(tools, GatheringToolsTest::taskTool);
        new GatheringTools(() -> 32, () -> 32).register(contributions);
        return contributions.taskDefinitions().get(name);
    }

    private static JsonObject args(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
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
