package dev.friendai.features.mining.minecraft.tool;

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

class BlockInfoToolsTest {
    @Test
    void contributesOnlyBlockInformationToolsWithTheirSchemasAndDefaultMetadata() {
        ToolRegistry<CompanionEntity> tools = new ToolRegistry<>();
        FeatureToolRegistry contributions = new FeatureToolRegistry(tools, definition -> {
            throw new AssertionError("block information tools are not task tools");
        });

        new BlockInfoTools(() -> 24, () -> 20, companion -> null).register(contributions);

        assertEquals(List.of("find_trees", "find_blocks", "describe_block"), names(tools, AccessLevel.TRUSTED));
        assertEquals(List.of(), names(tools, AccessLevel.GUEST));
        assertEquals(
                Map.of("find_trees", schema("""
                                {
                                  "type":"object",
                                  "properties":{
                                    "type":{"type":"string","description":"Log block id like minecraft:oak_log, or a tag like #minecraft:logs"},
                                    "near":{"type":"string","description":"Search around yourself (default), the speaker (me), or a remembered place (place:<name>)"},
                                    "x":{"type":"integer","description":"Block x coordinate to search around instead of near","minimum":-30000000,"maximum":30000000},
                                    "y":{"type":"integer","description":"Block y coordinate to search around instead of near","minimum":-64,"maximum":320},
                                    "z":{"type":"integer","description":"Block z coordinate to search around instead of near","minimum":-30000000,"maximum":30000000},
                                    "radius":{"type":"integer","description":"Search radius in blocks","minimum":4,"maximum":64},
                                    "limit":{"type":"integer","description":"How many trees to list","minimum":1,"maximum":10}
                                  }
                                }
                                """), "find_blocks", schema("""
                                {
                                  "type":"object",
                                  "properties":{
                                    "block":{"type":"string","description":"Block id like minecraft:iron_ore, or a tag like #minecraft:logs"},
                                    "radius":{"type":"integer","description":"Search radius in blocks","minimum":1,"maximum":64},
                                    "limit":{"type":"integer","description":"How many blocks to list","minimum":1,"maximum":10}
                                  },
                                  "required":["block"]
                                }
                                """), "describe_block", schema("""
                                {
                                  "type":"object",
                                  "properties":{
                                    "x":{"type":"integer","description":"Block x coordinate","minimum":-30000000,"maximum":30000000},
                                    "y":{"type":"integer","description":"Block y coordinate","minimum":-64,"maximum":320},
                                    "z":{"type":"integer","description":"Block z coordinate","minimum":-30000000,"maximum":30000000}
                                  }
                                }
                                """)),
                tools.specs(AccessLevel.TRUSTED).stream()
                        .collect(Collectors.toMap(spec -> spec.name(), spec -> spec.parametersSchema())));
        assertEquals(
                List.of("find_trees", "find_blocks", "describe_block"),
                contributions.catalogMetadata().stream()
                        .map(meta -> meta.name())
                        .toList());
        assertEquals(
                contributions.catalogMetadata(),
                List.of("find_trees", "find_blocks", "describe_block").stream()
                        .map(name -> ToolCatalog.defaults().meta(name).orElseThrow())
                        .toList());
        assertEquals(Map.of(), contributions.taskDefinitions());
    }

    private static List<String> names(ToolRegistry<CompanionEntity> tools, AccessLevel access) {
        return tools.specs(access).stream().map(spec -> spec.name()).toList();
    }

    private static JsonObject schema(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }
}
