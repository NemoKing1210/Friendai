package dev.friedai.tool.builtin;

import dev.friedai.entity.CompanionEntity;
import dev.friedai.perception.PerceptionBuilder;
import dev.friedai.task.mining.BlockMatcher;
import dev.friedai.task.mining.BlockSearch;
import dev.friedai.tool.SchemaBuilder;
import dev.friedai.tool.ToolRegistry;
import dev.friedai.tool.ToolResult;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.IntSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/** Looking at the world: descriptions and searches the LLM uses to plan. */
final class InfoTools {
    private InfoTools() {}

    static void register(ToolRegistry<CompanionEntity> registry, List<TaskToolDef> defs, IntSupplier mineRadius) {
        registry.register(ToolKit.instantTool("look_around", "Describe the surroundings in detail.",
                (c, a) -> ToolResult.ok(PerceptionBuilder.detailed(c).toPromptText())));
        registry.register(ToolKit.instantTool("find_blocks",
                "List the nearest blocks of a given id or #tag with coordinates. Use it before planning.",
                SchemaBuilder.object()
                        .string("block", "Block id like minecraft:iron_ore, or a tag like #minecraft:logs")
                        .integer("radius", "Search radius in blocks", 1, 64)
                        .integer("limit", "How many blocks to list", 1, 10)
                        .required("block")
                        .build(),
                (c, a) -> {
                    BlockMatcher matcher = ToolKit.parseBlockMatcher(a.get("block").getAsString());
                    int radius = a.has("radius") ? a.get("radius").getAsInt() : mineRadius.getAsInt();
                    int limit = a.has("limit") ? a.get("limit").getAsInt() : 5;
                    List<BlockPos> found = BlockSearch.nearestN(
                            (ServerLevel) c.level(), c.blockPosition(), radius, matcher, Set.of(), limit);
                    if (found.isEmpty()) {
                        return ToolResult.ok("no " + matcher.label() + " within " + radius + " blocks");
                    }
                    StringBuilder text = new StringBuilder(found.size() + " x " + matcher.label() + " nearest first: ");
                    for (int i = 0; i < found.size(); i++) {
                        BlockPos p = found.get(i);
                        if (i > 0) {
                            text.append(", ");
                        }
                        double distance = Math.sqrt(Vec3.atCenterOf(p).distanceToSqr(c.position()));
                        text.append(p.getX()).append(' ').append(p.getY()).append(' ').append(p.getZ())
                                .append(" (").append(String.format(Locale.ROOT, "%.1f", distance)).append(" blocks)");
                    }
                    return ToolResult.ok(text.toString());
                }));
    }
}
