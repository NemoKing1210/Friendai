package dev.friendai.tool.builtin;

import com.google.gson.JsonObject;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.core.FriendaiServerState;
import dev.friendai.task.TaskSettings;
import dev.friendai.task.builtin.FellTreeTask;
import dev.friendai.task.builtin.MineBlockAtTask;
import dev.friendai.task.builtin.MineBlocksTask;
import dev.friendai.task.builtin.PickupItemsTask;
import dev.friendai.task.mining.BlockMatcher;
import dev.friendai.task.mining.TreeFinder;
import dev.friendai.tool.AccessLevel;
import dev.friendai.tool.Caller;
import dev.friendai.tool.SchemaBuilder;
import dev.friendai.tool.ToolRegistry;
import dev.friendai.tool.ToolResult;
import java.util.List;
import java.util.Set;
import java.util.function.IntSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** Getting resources: mining blocks and picking up what is lying around. */
final class GatheringTools {
    private GatheringTools() {}

    static void register(
            ToolRegistry<CompanionEntity> registry,
            List<TaskToolDef> defs,
            IntSupplier mineRadius,
            IntSupplier treeRadius) {
        var fellSchema = SchemaBuilder.object()
                .string("type", "Log block id like minecraft:oak_log, or a tag like #minecraft:logs. "
                        + "Naming one block id also allows felling a log structure of that wood")
                .enumeration("near", "Fell a tree near the speaker (me, default) or near yourself (self)",
                        "me", "self")
                .integer("x", "Block x coordinate to search around instead of near", -30000000, 30000000)
                .integer("y", "Block y coordinate to search around instead of near", -64, 320)
                .integer("z", "Block z coordinate to search around instead of near", -30000000, 30000000)
                .integer("radius", "Search radius in blocks", 4, 64)
                .integer("limit", "Cut at most this many logs and stop; omit to fell the whole tree", 1, 256)
                .bool("collect", "Whether to pick up the drops afterwards; default follows the server setting")
                .build();
        String fellDescription = "Fell one whole tree from the lowest log up and collect the logs. Picks the "
                + "nearest real tree (leaves, or the leftovers of one) around the speaker or yourself; a log "
                + "structure such as a cabin is never felled unless its block id is named. Use find_trees first "
                + "when the choice matters.";
        ToolKit.addCaller(registry, defs,
                new TaskToolDef("fell_tree", fellDescription, fellSchema,
                        (c, a) -> createFellTask(c, new Caller(null, "", AccessLevel.TRUSTED), a, treeRadius), true,
                        AccessLevel.TRUSTED, TaskToolDef.QueueTraits.DEFAULT,
                        (c, caller, a) -> createFellTask(c, caller, a, treeRadius)),
                (c, caller, a) -> {
                    try {
                        return ToolKit.startTask(c, "fell_tree", createFellTask(c, caller, a, treeRadius));
                    } catch (IllegalArgumentException e) {
                        return ToolResult.error(e.getMessage());
                    }
                });
        ToolKit.addCaller(registry, defs,
                new TaskToolDef("mine_blocks",
                        "Mine N blocks of the given block id or #tag near the companion and collect the drops. "
                                + "near: me searches around the speaker.",
                        SchemaBuilder.object()
                                .string("block", "Block id like minecraft:oak_log, or a tag like #minecraft:logs")
                                .integer("count", "How many blocks to mine", 1, 64)
                                .enumeration("near", "Search around yourself (self, default) or around the speaker (me)",
                                        "self", "me")
                                .integer("x", "Block x coordinate to search around instead of near",
                                        -30000000, 30000000)
                                .integer("y", "Block y coordinate to search around instead of near", -64, 320)
                                .integer("z", "Block z coordinate to search around instead of near",
                                        -30000000, 30000000)
                                .bool("collect",
                                        "Whether to pick up the drops afterwards; default follows the server setting")
                                .required("block", "count")
                                .build(),
                        (c, a) -> createMineTask(c, new Caller(null, "", AccessLevel.TRUSTED), a, mineRadius),
                        true, AccessLevel.TRUSTED, new TaskToolDef.QueueTraits(false, "count"),
                        (c, caller, a) -> createMineTask(c, caller, a, mineRadius)),
                (c, caller, a) -> {
                    try {
                        return ToolKit.startTask(c, "mine_blocks", createMineTask(c, caller, a, mineRadius));
                    } catch (IllegalArgumentException e) {
                        return ToolResult.error(e.getMessage());
                    }
                });
        ToolKit.add(registry, defs, new TaskToolDef("mine_block_at", "Mine the block at the given coordinates and collect the drops.",
                SchemaBuilder.object()
                        .integer("x", "Block X coordinate")
                        .integer("y", "Block Y coordinate")
                        .integer("z", "Block Z coordinate")
                        .required("x", "y", "z")
                        .build(),
                (c, a) -> {
                    int x = a.get("x").getAsInt();
                    int y = a.get("y").getAsInt();
                    int z = a.get("z").getAsInt();
                    if (y < c.level().getMinY() || y > c.level().getMaxY()) {
                        throw new IllegalArgumentException("y is outside the world (" + c.level().getMinY() + ".." + c.level().getMaxY() + ")");
                    }
                    if (!c.level().hasChunkAt(x, z)) {
                        throw new IllegalArgumentException("the chunk at " + x + " " + z + " is not loaded");
                    }
                    return new MineBlockAtTask(new BlockPos(x, y, z));
                }, true));
        ToolKit.add(registry, defs, new TaskToolDef("pickup_items", "Pick up dropped items within the given radius.",
                SchemaBuilder.object()
                        .integer("radius", "Search radius in blocks", 1, 16)
                        .string("item", "Item id like minecraft:egg, or a tag; only these are picked up")
                        .required("radius")
                        .build(),
                (c, a) -> new PickupItemsTask(
                        a.get("radius").getAsInt(),
                        a.has("item") && !a.get("item").isJsonNull()
                                ? ToolKit.parseItemMatcher(a.get("item").getAsString())
                                : null),
                true));
    }

    private static MineBlocksTask createMineTask(
            CompanionEntity c, Caller caller, JsonObject a, IntSupplier mineRadius) {
        BlockMatcher matcher = ToolKit.parseBlockMatcher(a.get("block").getAsString());
        // Unlike the tree tools, mining searches around the companion unless the speaker or a place is named.
        boolean named = ToolKit.optionalPos(a).isPresent()
                || (a.has("near") && !a.get("near").isJsonNull() && a.get("near").getAsString().equals("me"));
        BlockPos origin = named ? NearResolver.resolve(c, caller, a) : null;
        boolean collect = a.has("collect") && !a.get("collect").isJsonNull()
                ? a.get("collect").getAsBoolean()
                : TaskSettings.mineCollectDrops;
        return new MineBlocksTask(matcher, a.get("count").getAsInt(), mineRadius.getAsInt(), origin, collect);
    }

    /** Picks the tree for a {@code fell_tree} call; throws {@link IllegalArgumentException} with the reason. */
    private static FellTreeTask createFellTask(
            CompanionEntity c, Caller caller, JsonObject a, IntSupplier treeRadius) {
        BlockPos origin = NearResolver.resolve(c, caller, a);
        int radius = a.has("radius") ? a.get("radius").getAsInt() : treeRadius.getAsInt();
        BlockMatcher matcher = a.has("type") && !a.get("type").isJsonNull()
                ? ToolKit.parseBlockMatcher(a.get("type").getAsString())
                : null;
        // A named block id is the player's explicit word; a tag or no type must never pick a log structure.
        boolean treesOnly = matcher == null || matcher.label().startsWith("#");
        ServerLevel level = (ServerLevel) c.level();
        var resources = FriendaiServerState.get(level.getServer()).resources();
        BlockMatcher logs = matcher != null ? matcher : BlockMatcher.parse("#minecraft:logs").orElseThrow();
        var observed = resources.visibleBlockIds(c, radius).entrySet().stream()
                .filter(entry -> entry.getKey().distSqr(origin) <= (double) radius * radius)
                .filter(entry -> {
                    var id = net.minecraft.resources.Identifier.tryParse(entry.getValue());
                    return id != null && net.minecraft.core.registries.BuiltInRegistries.BLOCK.containsKey(id)
                            && logs.test(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(id).defaultBlockState());
                }).collect(java.util.stream.Collectors.toMap(
                        (java.util.Map.Entry<BlockPos, String> entry) -> entry.getKey(),
                        (java.util.Map.Entry<BlockPos, String> entry) -> entry.getValue()));
        var leavesMatcher = BlockMatcher.parse("#minecraft:leaves").orElseThrow();
        Set<BlockPos> leaves = resources.visible(c, leavesMatcher, radius, 1024).stream()
                .filter(pos -> pos.distSqr(origin) <= (double) radius * radius)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        List<TreeFinder.TreeInfo> found = TreeFinder.findObserved(level, origin, observed, leaves, 1).stream()
                .filter(tree -> !treesOnly || tree.kind() != TreeFinder.Kind.LOG_STRUCTURE).toList();
        if (found.isEmpty()) {
            String where = origin.getX() + " " + origin.getY() + " " + origin.getZ();
            String hint = "";
            if (treesOnly) {
                List<TreeFinder.TreeInfo> structures = TreeFinder.findObserved(level, origin, observed, leaves, 1);
                if (!structures.isEmpty()) {
                    TreeFinder.TreeInfo s = structures.get(0);
                    hint = "; the nearest " + s.type() + " is a log structure at " + s.base().getX() + " "
                            + s.base().getY() + " " + s.base().getZ();
                }
            }
            throw new IllegalArgumentException("no tree within " + radius + " blocks of " + where + hint
                    + "; use mine_blocks to take named blocks");
        }
        int limit = a.has("limit") ? a.get("limit").getAsInt() : 0;
        boolean collect = a.has("collect") && !a.get("collect").isJsonNull()
                ? a.get("collect").getAsBoolean()
                : TaskSettings.fellCollectDrops;
        return new FellTreeTask(found.get(0), logs, limit, collect);
    }
}
