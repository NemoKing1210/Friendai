package dev.friendai.tool.builtin;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.perception.AreaMapBuilder;
import dev.friendai.perception.OwnerFocusBuilder;
import dev.friendai.perception.PerceptionBuilder;
import dev.friendai.perception.PerceptionSnapshot;
import dev.friendai.perception.TreeSummary;
import dev.friendai.perception.TreeText;
import dev.friendai.task.mining.BlockMatcher;
import dev.friendai.task.mining.BlockSearch;
import dev.friendai.task.mining.TreeFinder;
import dev.friendai.tool.AccessLevel;
import dev.friendai.tool.SchemaBuilder;
import dev.friendai.tool.ToolRegistry;
import dev.friendai.tool.ToolResult;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Looking at the world: descriptions and searches the LLM uses to plan. */
final class InfoTools {
    private InfoTools() {}

    static void register(
            ToolRegistry<CompanionEntity> registry,
            List<TaskToolDef> defs,
            IntSupplier mineRadius,
            IntSupplier treeRadius) {
        registry.register(ToolKit.callerTool("find_trees",
                "List the nearest trees with their kind, height, log count, distance and direction. A log structure "
                        + "has no leaves and is not felled automatically. Use it to choose a tree before fell_tree.",
                SchemaBuilder.object()
                        .string("type", "Log block id like minecraft:oak_log, or a tag like #minecraft:logs")
                        .enumeration("near", "Search around the speaker (me, default) or around yourself (self)",
                                "me", "self")
                        .integer("x", "Block x coordinate to search around instead of near", -30000000, 30000000)
                        .integer("y", "Block y coordinate to search around instead of near", -64, 320)
                        .integer("z", "Block z coordinate to search around instead of near", -30000000, 30000000)
                        .integer("radius", "Search radius in blocks", 4, 64)
                        .integer("limit", "How many trees to list", 1, 10)
                        .build(),
                AccessLevel.TRUSTED,
                () -> true,
                (c, caller, a) -> {
                    BlockPos origin = NearResolver.resolve(c, caller, a);
                    BlockMatcher matcher = a.has("type") && !a.get("type").isJsonNull()
                            ? ToolKit.parseBlockMatcher(a.get("type").getAsString())
                            : null;
                    int radius = a.has("radius") ? a.get("radius").getAsInt() : treeRadius.getAsInt();
                    int limit = a.has("limit") ? a.get("limit").getAsInt() : 5;
                    List<TreeFinder.TreeInfo> found =
                            TreeFinder.find((ServerLevel) c.level(), origin, radius, matcher, limit, false);
                    List<TreeSummary> trees = found.stream()
                            .filter(t -> t.kind() != TreeFinder.Kind.LOG_STRUCTURE)
                            .map(TreeFinder.TreeInfo::summary)
                            .toList();
                    List<TreeSummary> structures = found.stream()
                            .filter(t -> t.kind() == TreeFinder.Kind.LOG_STRUCTURE)
                            .map(TreeFinder.TreeInfo::summary)
                            .toList();
                    return ToolResult.ok(TreeText.toolListing(trees, structures, radius));
                }));
        registry.register(ToolKit.instantTool("look_around", "Describe the surroundings in detail.",
                AccessLevel.GUEST,
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
        registry.register(ToolKit.callerTool("player_view",
                "Where the player who is talking to you stands and what they are looking at or standing on, "
                        + "right now. Use it when they say 'this block', 'the one I'm looking at' or 'where I stand', "
                        + "especially after they may have moved.",
                SchemaBuilder.object().build(),
                AccessLevel.TRUSTED,
                () -> true,
                (c, caller, a) -> ToolResult.ok(OwnerFocusBuilder.of(c, caller.uuid()).toPromptText())));
        registry.register(ToolKit.callerTool("describe_block",
                "Facts about a block: id, hardness, whether it can be dug by hand or needs a proper tool "
                        + "(and whether you have one), plus the light level there. Omit x, y and z to describe the "
                        + "block the speaker is looking at.",
                SchemaBuilder.object()
                        .integer("x", "Block x coordinate", -30000000, 30000000)
                        .integer("y", "Block y coordinate", -64, 320)
                        .integer("z", "Block z coordinate", -30000000, 30000000)
                        .build(),
                AccessLevel.TRUSTED,
                () -> true,
                (c, caller, a) -> {
                    Optional<BlockPos> asked = ToolKit.optionalPos(a);
                    BlockPos pos = asked.orElse(null);
                    if (pos == null) {
                        pos = OwnerFocusBuilder.lookedAtBlock(c, caller.uuid()).orElse(null);
                    }
                    if (pos == null) {
                        return ToolResult.error("no block in view; pass x, y and z");
                    }
                    ServerLevel level = (ServerLevel) c.level();
                    if (!level.hasChunkAt(pos)) {
                        return ToolResult.error("that chunk is not loaded");
                    }
                    BlockState state = level.getBlockState(pos);
                    String at = pos.getX() + " " + pos.getY() + " " + pos.getZ();
                    int light = level.getMaxLocalRawBrightness(pos);
                    if (state.isAir()) {
                        return ToolResult.ok("air at " + at + "; light " + light);
                    }
                    String name = shortName(BuiltInRegistries.BLOCK.getKey(state.getBlock()));
                    float hardness = state.getDestroySpeed(level, pos);
                    if (hardness < 0) {
                        return ToolResult.ok(name + " at " + at + ": cannot be broken; light " + light);
                    }
                    String facts = name + " at " + at + ": hardness " + PerceptionSnapshot.number(hardness);
                    if (!state.requiresCorrectToolForDrops()) {
                        return ToolResult.ok(facts + ", can be dug by hand; light " + light);
                    }
                    String tool = OwnerFocusBuilder.toolFor(c, state);
                    return ToolResult.ok(facts + ", needs a proper tool (you have "
                            + (tool == null ? "none" : tool) + "); light " + light);
                }));
        registry.register(ToolKit.callerTool("survey_area",
                "Draw a top-down map of the area at your eye level. Legend: @ you, O the speaker, Z hostile, "
                        + "# solid, ~ water, ^ lava or fire, . air, ? unloaded chunk. Use it to find a way around "
                        + "walls or to see where a threat stands.",
                SchemaBuilder.object()
                        .integer("radius", "Map radius in blocks", 3, 16)
                        .build(),
                AccessLevel.TRUSTED,
                () -> true,
                (c, caller, a) -> {
                    int radius = a.has("radius") ? a.get("radius").getAsInt() : 8;
                    return ToolResult.ok(AreaMapBuilder.of(c, caller.uuid(), radius));
                }));
    }

    private static String shortName(Identifier id) {
        return id.getNamespace().equals("minecraft") ? id.getPath() : id.toString();
    }
}
