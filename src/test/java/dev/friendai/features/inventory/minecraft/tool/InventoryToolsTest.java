package dev.friendai.features.inventory.minecraft.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.ArgValidator;
import dev.friendai.execution.tool.Caller;
import dev.friendai.execution.tool.Tool;
import dev.friendai.execution.tool.ToolRegistry;
import dev.friendai.server.execution.tools.FeatureToolRegistry;
import dev.friendai.server.execution.tools.QueueToolAccess;
import dev.friendai.server.execution.tools.TaskToolRuntime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InventoryToolsTest {
    private static final QueueToolAccess NO_ACCESS = new QueueToolAccess() {
        @Override
        public Caller currentCaller(CompanionEntity companion, UUID principal) {
            return new Caller(null, "", AccessLevel.OWNER);
        }

        @Override
        public void linkPlan(CompanionEntity companion, long planId) {}
    };

    @Test
    void giveItemsAddsOptionalRetainAndStrictArgumentsWithoutBreakingLegacyCalls() {
        JsonObject schema = giveItems().schema();
        JsonObject properties = schema.getAsJsonObject("properties");

        assertTrue(properties.has("retain"));
        assertEquals("integer", properties.getAsJsonObject("retain").get("type").getAsString());
        assertEquals(0, properties.getAsJsonObject("retain").get("minimum").getAsInt());
        assertEquals(64, properties.getAsJsonObject("retain").get("maximum").getAsInt());
        assertTrue(properties.has("strict"));
        assertEquals("boolean", properties.getAsJsonObject("strict").get("type").getAsString());
        assertEquals(
                0,
                ArgValidator.validate(schema, json("{\"item\":\"minecraft:stone_axe\",\"count\":1}"))
                        .size());
        assertTrue(ArgValidator.validate(
                                schema,
                                json("{\"item\":\"minecraft:stone_axe\",\"count\":1,\"retain\":65,\"strict\":true}"))
                        .size()
                > 0);
    }

    private static Tool<CompanionEntity> giveItems() {
        Map<String, Tool<CompanionEntity>> tools = new LinkedHashMap<>();
        FeatureToolRegistry registry = new FeatureToolRegistry(new ToolRegistry<>(), definition -> {
            tools.put(definition.name(), TaskToolRuntime.tool(definition, NO_ACCESS));
            return tools.get(definition.name());
        });
        new InventoryTools().register(registry);
        return tools.get("give_items");
    }

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }
}
