package dev.friendai.features.placements.minecraft.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.friendai.agent.tools.ToolCatalog;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.ToolRegistry;
import dev.friendai.server.execution.tools.FeatureToolRegistry;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class PlacedToolsTest {
    @Test
    void contributesOnlyThePlacedBlockQueryWithItsSchemaAndDefaultMetadata() {
        ToolRegistry<CompanionEntity> tools = new ToolRegistry<>();
        FeatureToolRegistry contributions = new FeatureToolRegistry(tools, definition -> {
            throw new AssertionError("placed block query is not a task tool");
        });

        new PlacedTools().register(contributions);

        assertEquals(List.of("find_placed"), names(tools, AccessLevel.TRUSTED));
        assertEquals(List.of(), names(tools, AccessLevel.GUEST));
        assertEquals(
                Map.of("find_placed", schema("""
                        {
                          "type":"object",
                          "properties":{
                            "block":{"type":"string","description":"Block id like minecraft:oak_log, or a tag like #minecraft:logs"},
                            "radius":{"type":"integer","description":"Search radius in blocks","minimum":1,"maximum":128},
                            "limit":{"type":"integer","description":"How many blocks to list","minimum":1,"maximum":20}
                          }
                        }
                        """)),
                tools.specs(AccessLevel.TRUSTED).stream()
                        .collect(Collectors.toMap(spec -> spec.name(), spec -> spec.parametersSchema())));
        assertEquals(
                List.of(ToolCatalog.defaults().meta("find_placed").orElseThrow()), contributions.catalogMetadata());
        assertEquals(Map.of(), contributions.taskDefinitions());
    }

    private static List<String> names(ToolRegistry<CompanionEntity> tools, AccessLevel access) {
        return tools.specs(access).stream().map(spec -> spec.name()).toList();
    }

    private static JsonObject schema(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }
}
