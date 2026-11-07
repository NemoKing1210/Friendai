package dev.friendai.features.acquisition.minecraft.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.execution.tool.Tool;
import dev.friendai.execution.tool.ToolRegistry;
import dev.friendai.server.execution.tools.FeatureToolRegistry;
import dev.friendai.server.execution.tools.MinecraftObtainSubtaskFactory;
import dev.friendai.server.execution.tools.QueueToolAccess;
import dev.friendai.server.execution.tools.TaskToolRuntime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ObtainToolsAliasTest {
    private static final QueueToolAccess NO_ACCESS = new QueueToolAccess() {
        @Override
        public Caller currentCaller(CompanionEntity companion, UUID principal) {
            return new Caller(null, "", AccessLevel.OWNER);
        }

        @Override
        public void linkPlan(CompanionEntity companion, long planId) {}
    };

    @Test
    void obtainItemsAcceptsTheDocumentedSingleItemAlias() {
        JsonObject normalized =
                obtainItems().normalizeArguments(json("{\"item\":\"minecraft:cobblestone\",\"count\":36}"));

        assertTrue(normalized.has("items"));
        assertFalse(normalized.has("item"));
        assertEquals(1, normalized.getAsJsonArray("items").size());
        JsonObject entry = normalized.getAsJsonArray("items").get(0).getAsJsonObject();
        assertEquals("minecraft:cobblestone", entry.get("item").getAsString());
        assertEquals(36, entry.get("count").getAsInt());
    }

    @Test
    void theAliasKeepsTheOtherArguments() {
        JsonObject normalized = obtainItems()
                .normalizeArguments(
                        json("{\"item\":\"minecraft:stone_pickaxe\",\"count\":1,\"scope\":\"expedition\"}"));

        assertEquals("expedition", normalized.get("scope").getAsString());
        assertEquals(
                1,
                normalized
                        .getAsJsonArray("items")
                        .get(0)
                        .getAsJsonObject()
                        .get("count")
                        .getAsInt());
    }

    @Test
    void theAliasDefaultsAMissingCountToOne() {
        JsonObject normalized = obtainItems().normalizeArguments(json("{\"item\":\"minecraft:stick\"}"));

        assertEquals(
                1,
                normalized
                        .getAsJsonArray("items")
                        .get(0)
                        .getAsJsonObject()
                        .get("count")
                        .getAsInt());
    }

    @Test
    void theCanonicalFormIsPassedThroughUntouched() {
        JsonObject canonical = json("{\"items\":[{\"item\":\"minecraft:stone\",\"count\":2}]}");

        assertSame(canonical, obtainItems().normalizeArguments(canonical));
    }

    @Test
    void otherObtainToolsKeepTheirArguments() {
        JsonObject args = json("{\"item\":\"minecraft:stone\",\"count\":1}");

        assertSame(args, tools().get("obtain_item").normalizeArguments(args));
    }

    private static Tool<CompanionEntity> obtainItems() {
        return tools().get("obtain_items");
    }

    private static Map<String, Tool<CompanionEntity>> tools() {
        Map<String, Tool<CompanionEntity>> contributed = new LinkedHashMap<>();
        FeatureToolRegistry registry = new FeatureToolRegistry(new ToolRegistry<>(), definition -> {
            Tool<CompanionEntity> tool = TaskToolRuntime.tool(definition, NO_ACCESS);
            contributed.put(definition.name(), tool);
            return tool;
        });
        new ObtainTools(new MinecraftObtainSubtaskFactory()).register(registry);
        return contributed;
    }

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }
}
