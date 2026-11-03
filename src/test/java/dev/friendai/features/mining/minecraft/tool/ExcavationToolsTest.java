package dev.friendai.features.mining.minecraft.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.friendai.agent.tools.ToolCatalog;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.execution.tool.Tool;
import dev.friendai.execution.tool.ToolRegistry;
import dev.friendai.execution.tool.ToolResult;
import dev.friendai.features.places.minecraft.PlaceService;
import dev.friendai.server.execution.tools.FeatureToolRegistry;
import dev.friendai.server.execution.tools.TaskToolDefinition;
import org.junit.jupiter.api.Test;

class ExcavationToolsTest {
    @Test
    void schemaCarriesEveryOptionAndIsGatheringTrusted() {
        TaskToolDefinition definition = definition();
        JsonObject properties = definition.schema().getAsJsonObject("properties");

        for (String field : new String[] {
            "shape",
            "box",
            "run",
            "start",
            "count",
            "until_item",
            "until_count",
            "collect",
            "containers",
            "allow_liquids",
            "require_tool",
            "reserve_durability"
        }) {
            assertTrue(properties.has(field), field);
        }
        assertTrue(
                properties.getAsJsonObject("box").getAsJsonObject("properties").has("x1"));
        assertTrue(
                properties.getAsJsonObject("run").getAsJsonObject("properties").has("direction"));
        assertTrue(properties
                .getAsJsonObject("start")
                .getAsJsonObject("properties")
                .has("near"));
        assertTrue(definition.plannable());
        assertTrue(definition.restorable());
        assertTrue(definition.checkpointType().equals("excavate"));
        assertTrue(definition.minAccess() == AccessLevel.TRUSTED);
        assertEquals(
                definition.catalogMeta(),
                ToolCatalog.defaults().meta("excavate").orElseThrow());
    }

    @Test
    void boxRejectsCorridorFieldsAndUnknownArguments() {
        assertThrows(
                IllegalArgumentException.class,
                () -> definition().callerFactory().create(null, new Caller(null, "", AccessLevel.TRUSTED), args("""
                        {"shape":"box","box":{"x1":0,"y1":0,"z1":0,"x2":1,"y2":1,"z2":1},"run":{"direction":"east"}}
                        """)));
        IllegalArgumentException unknown = assertThrows(
                IllegalArgumentException.class,
                () -> definition().callerFactory().create(null, new Caller(null, "", AccessLevel.TRUSTED), args("""
                        {"shape":"box","box":{"x1":0,"y1":0,"z1":0,"x2":1,"y2":1,"z2":1},"wat":1}
                        """)));
        assertTrue(unknown.getMessage().contains("wat"));
    }

    @Test
    void aBoxWithoutCornersFailsBeforeTheWorldIsTouched() {
        assertThrows(
                IllegalArgumentException.class,
                () -> definition().callerFactory().create(null, new Caller(null, "", AccessLevel.TRUSTED), args("""
                        {"shape":"box","box":{"x1":0,"y1":0,"z1":0}}
                        """)));
    }

    private static TaskToolDefinition definition() {
        ToolRegistry<CompanionEntity> tools = new ToolRegistry<>();
        FeatureToolRegistry contributions = new FeatureToolRegistry(tools, ExcavationToolsTest::taskTool);
        new ExcavationTools(() -> true, PlaceService.unavailable()).register(contributions);
        return contributions.taskDefinitions().get("excavate");
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
