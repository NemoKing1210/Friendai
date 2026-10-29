package dev.friendai.features.placements.minecraft.tool;

import com.google.gson.JsonObject;
import dev.friendai.agent.tools.Cost;
import dev.friendai.agent.tools.ToolGroup;
import dev.friendai.agent.tools.ToolMeta;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.tool.SchemaBuilder;
import dev.friendai.execution.tool.Tool;
import dev.friendai.execution.tool.ToolResult;
import dev.friendai.features.mining.minecraft.mining.BlockMatcher;
import dev.friendai.features.navigation.pathing.Pos;
import dev.friendai.features.placements.PlacedQuery;
import dev.friendai.features.placements.PlacedText;
import dev.friendai.placed.PlacedService;
import dev.friendai.server.execution.tools.FeatureToolContributor;
import dev.friendai.server.execution.tools.FeatureToolRegistry;
import java.util.List;
import java.util.Objects;

/** Queries the companion's surviving placed blocks. */
public final class PlacedTools implements FeatureToolContributor {
    @Override
    public void register(FeatureToolRegistry registry) {
        Objects.requireNonNull(registry).registerTool(tool(), metadata("find_placed"));
    }

    private static Tool<CompanionEntity> tool() {
        return new Tool<>() {
            @Override
            public String name() {
                return "find_placed";
            }

            @Override
            public String description() {
                return "List blocks you placed that are still in the world, nearest first, with coordinates, distance "
                        + "and what placed them. Use it when the player asks what you just placed or tells you to "
                        + "take those blocks back; mine_blocks, pickup_items and store_items take placed:true.";
            }

            @Override
            public JsonObject schema() {
                return SchemaBuilder.object()
                        .string("block", "Block id like minecraft:oak_log, or a tag like #minecraft:logs")
                        .integer("radius", "Search radius in blocks", 1, 128)
                        .integer("limit", "How many blocks to list", 1, 20)
                        .build();
            }

            @Override
            public ToolResult execute(CompanionEntity companion, JsonObject args) {
                BlockMatcher matcher = args.has("block") && !args.get("block").isJsonNull()
                        ? parseBlockMatcher(args.get("block").getAsString())
                        : null;
                int radius = args.has("radius") ? args.get("radius").getAsInt() : 32;
                int limit = args.has("limit") ? args.get("limit").getAsInt() : 8;
                PlacedQuery.Result result = PlacedService.find(companion, matcher, radius, limit);
                net.minecraft.core.BlockPos here = companion.blockPosition();
                return ToolResult.ok(PlacedText.listing(
                        result,
                        matcher == null ? null : matcher.label(),
                        radius,
                        new Pos(here.getX(), here.getY(), here.getZ())));
            }
        };
    }

    private static BlockMatcher parseBlockMatcher(String value) {
        return BlockMatcher.parse(value)
                .orElseThrow(() -> new IllegalArgumentException("unknown block or tag: " + value));
    }

    private static ToolMeta metadata(String name) {
        return switch (name) {
            case "find_placed" -> new ToolMeta(
                    name,
                    ToolGroup.INFO,
                    false,
                    "- Use find_placed when the player asks what you just placed or tells you to take those blocks back. "
                            + "mine_blocks, pickup_items and store_items take placed:true to act on your own placements.",
                    List.of(),
                    Cost.LOW);
            default -> throw new IllegalArgumentException("unknown placement tool: " + name);
        };
    }
}
