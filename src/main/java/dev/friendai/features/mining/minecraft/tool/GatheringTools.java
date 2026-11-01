package dev.friendai.features.mining.minecraft.tool;

import com.google.gson.JsonObject;
import dev.friendai.agent.tools.Cost;
import dev.friendai.agent.tools.ToolGroup;
import dev.friendai.agent.tools.ToolMeta;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.execution.tool.SchemaBuilder;
import dev.friendai.features.mining.minecraft.mining.BlockMatcher;
import dev.friendai.features.mining.minecraft.mining.TreeFinder;
import dev.friendai.features.mining.minecraft.task.FellTreeSearchTask;
import dev.friendai.features.mining.minecraft.task.FellTreeTask;
import dev.friendai.features.mining.minecraft.task.MineBlockAtTask;
import dev.friendai.features.mining.minecraft.task.MineBlocksTask;
import dev.friendai.features.places.minecraft.NearResolver;
import dev.friendai.features.places.minecraft.PlaceService;
import dev.friendai.perception.resource.ResourceCandidates;
import dev.friendai.server.execution.tools.FeatureToolContributor;
import dev.friendai.server.execution.tools.FeatureToolRegistry;
import dev.friendai.server.execution.tools.TaskToolDefinition;
import dev.friendai.task.Task;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.IntSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** Getting resources: mining blocks and picking up what is lying around. */
public final class GatheringTools implements FeatureToolContributor {
    private final IntSupplier mineRadius;
    private final IntSupplier treeRadius;
    private final BooleanSupplier mineCollectDrops;
    private final BooleanSupplier fellCollectDrops;
    private final Function<CompanionEntity, ResourceCandidates> resourceCandidates;
    private final PlaceService places;

    public GatheringTools(IntSupplier mineRadius, IntSupplier treeRadius) {
        this(mineRadius, treeRadius, () -> true, () -> true);
    }

    public GatheringTools(
            IntSupplier mineRadius,
            IntSupplier treeRadius,
            BooleanSupplier mineCollectDrops,
            BooleanSupplier fellCollectDrops) {
        this(mineRadius, treeRadius, mineCollectDrops, fellCollectDrops, (ResourceCandidates) null);
    }

    public GatheringTools(
            IntSupplier mineRadius,
            IntSupplier treeRadius,
            BooleanSupplier mineCollectDrops,
            BooleanSupplier fellCollectDrops,
            ResourceCandidates resourceCandidates) {
        this(mineRadius, treeRadius, mineCollectDrops, fellCollectDrops, ignored -> resourceCandidates);
    }

    public GatheringTools(
            IntSupplier mineRadius,
            IntSupplier treeRadius,
            BooleanSupplier mineCollectDrops,
            BooleanSupplier fellCollectDrops,
            Function<CompanionEntity, ResourceCandidates> resourceCandidates) {
        this(
                mineRadius,
                treeRadius,
                mineCollectDrops,
                fellCollectDrops,
                resourceCandidates,
                PlaceService.unavailable());
    }

    public GatheringTools(
            IntSupplier mineRadius,
            IntSupplier treeRadius,
            BooleanSupplier mineCollectDrops,
            BooleanSupplier fellCollectDrops,
            Function<CompanionEntity, ResourceCandidates> resourceCandidates,
            PlaceService places) {
        this.mineRadius = mineRadius;
        this.treeRadius = treeRadius;
        this.mineCollectDrops = mineCollectDrops;
        this.fellCollectDrops = fellCollectDrops;
        this.resourceCandidates = resourceCandidates;
        this.places = java.util.Objects.requireNonNull(places);
    }

    @Override
    public void register(FeatureToolRegistry registry) {
        var fellSchema = SchemaBuilder.object()
                .string(
                        "type",
                        "Log block id like minecraft:oak_log, or a tag like #minecraft:logs. "
                                + "Naming one block id also allows felling a log structure of that wood")
                .string(
                        "near",
                        "Fell a tree around yourself (default), the speaker (me), or a remembered "
                                + "place (place:<name>)")
                .integer("x", "Block x coordinate to search around instead of near", -30000000, 30000000)
                .integer("y", "Block y coordinate to search around instead of near", -64, 320)
                .integer("z", "Block z coordinate to search around instead of near", -30000000, 30000000)
                .integer("radius", "Search radius in blocks", 4, 64)
                .integer(
                        "limit",
                        "Cut at most this many logs, from the top down, and stop; the trunk stays. "
                                + "Omit to fell the whole tree",
                        1,
                        256)
                .bool("collect", "Whether to pick up the drops afterwards; default follows the server setting")
                .build();
        String fellDescription = "Fell one whole tree: climb beside the trunk once and cut from the crown down, so "
                + "the logs and drops fall to the foot where they are collected. Picks the nearest real tree "
                + "(leaves, or the leftovers of one) around yourself; if no tree is in view it looks "
                + "around for a bounded time and starts when one is seen. A log structure such as a cabin is never "
                + "felled unless its block id is named. Use find_trees when the choice matters.";
        registry.registerTask(task(
                "fell_tree",
                fellDescription,
                fellSchema,
                (c, a) -> createFellTask(c, new Caller(null, "", AccessLevel.TRUSTED), a),
                (c, caller, a) -> createFellTask(c, caller, a),
                TaskToolDefinition.QueueTraits.DEFAULT));
        registry.registerTask(task(
                "mine_blocks",
                "Mine N blocks of the given block id or #tag near the companion and collect the drops. "
                        + "For a standing tree use fell_tree: it climbs once and cuts the whole tree, while this tool "
                        + "would mine one log at a time. "
                        + "Stone and ores need a pickaxe of sufficient tier; without one the task refuses "
                        + "instead of destroying the block. near: me searches around the speaker; place:<name> "
                        + "around a remembered place. For one block the owner pointed at, use mine_block_at: it mines "
                        + "that block and continues into its connected vein.",
                SchemaBuilder.object()
                        .string("block", "Block id like minecraft:oak_log, or a tag like #minecraft:logs")
                        .integer("count", "How many blocks to mine", 1, 64)
                        .string(
                                "near",
                                "Search around yourself (self, default), the speaker (me), or a "
                                        + "remembered place (place:<name>)")
                        .integer("x", "Block x coordinate to search around instead of near", -30000000, 30000000)
                        .integer("y", "Block y coordinate to search around instead of near", -64, 320)
                        .integer("z", "Block z coordinate to search around instead of near", -30000000, 30000000)
                        .bool("collect", "Whether to pick up the drops afterwards; default follows the server setting")
                        .bool(
                                "placed",
                                "Mine only blocks you placed yourself that are still in the world; "
                                        + "never an unplaced neighbour")
                        .required("block", "count")
                        .build(),
                (c, a) -> createMineTask(c, new Caller(null, "", AccessLevel.TRUSTED), a),
                (c, caller, a) -> createMineTask(c, caller, a),
                new TaskToolDefinition.QueueTraits(false, "count")));
        registry.registerTask(task(
                "mine_block_at",
                "Mine the block at the given coordinates and collect the drops. When the block is an ore or a log it "
                        + "continues into the whole connected vein of the same block (up to 32 blocks) and reports how "
                        + "many it mined and what it took. Use mine_blocks instead to collect a number of blocks of a "
                        + "kind found around you.",
                SchemaBuilder.object()
                        .integer("x", "Block X coordinate")
                        .integer("y", "Block Y coordinate")
                        .integer("z", "Block Z coordinate")
                        .required("x", "y", "z")
                        .build(),
                (c, a) -> createMineBlockAt(c, a),
                null,
                TaskToolDefinition.QueueTraits.DEFAULT));
    }

    private static TaskToolDefinition task(
            String name,
            String description,
            JsonObject schema,
            TaskToolDefinition.Factory factory,
            TaskToolDefinition.CallerFactory callerFactory,
            TaskToolDefinition.QueueTraits queueTraits) {
        return new TaskToolDefinition(
                name,
                description,
                schema,
                factory,
                callerFactory,
                true,
                AccessLevel.TRUSTED,
                queueTraits,
                true,
                true,
                metadata(name));
    }

    private MineBlocksTask createMineTask(CompanionEntity c, Caller caller, JsonObject a) {
        BlockMatcher matcher = parseBlockMatcher(a.get("block").getAsString());
        // An unnamed origin is the companion: null makes the task search around it with the mining radius, while a
        // named one (coordinates, a place or "me") is searched around that point instead.
        NearResolver.Origin origin = NearResolver.resolve(places, c, caller, a);
        boolean collect = a.has("collect") && !a.get("collect").isJsonNull()
                ? a.get("collect").getAsBoolean()
                : mineCollectDrops.getAsBoolean();
        boolean placed = a.has("placed")
                && !a.get("placed").isJsonNull()
                && a.get("placed").getAsBoolean();
        return new MineBlocksTask(
                matcher,
                a.get("count").getAsInt(),
                mineRadius.getAsInt(),
                origin.explicit() ? origin.pos() : null,
                collect,
                placed);
    }

    private Task createFellTask(CompanionEntity c, Caller caller, JsonObject a) {
        BlockPos origin = NearResolver.resolve(places, c, caller, a).pos();
        int radius = a.has("radius") ? a.get("radius").getAsInt() : treeRadius.getAsInt();
        BlockMatcher matcher = a.has("type") && !a.get("type").isJsonNull()
                ? parseBlockMatcher(a.get("type").getAsString())
                : null;
        // A named block id is the player's explicit word; a tag or no type must never pick a log structure.
        boolean treesOnly = matcher == null || matcher.label().startsWith("#");
        ServerLevel level = (ServerLevel) c.level();
        // The sight cache is companion-centred, but a call may search around its speaker. Expand only
        // the evidence radius here; the exact requested radius is applied around origin below.
        int evidenceRadius = Math.min(128, c.blockPosition().distManhattan(origin) + radius);
        BlockMatcher logs = matcher != null
                ? matcher
                : BlockMatcher.parse("#minecraft:logs").orElseThrow();
        ResourceCandidates resources = resourceCandidates.apply(c);
        Map<BlockPos, String> observedIds =
                resources == null ? Map.of() : resources.recentOrRememberedBlockIds(c, evidenceRadius, 1024);
        var observed = observedIds.entrySet().stream()
                .filter(entry -> entry.getKey().distSqr(origin) <= (double) radius * radius)
                .filter(entry -> {
                    var id = net.minecraft.resources.Identifier.tryParse(entry.getValue());
                    return id != null
                            && net.minecraft.core.registries.BuiltInRegistries.BLOCK.containsKey(id)
                            && logs.test(net.minecraft.core.registries.BuiltInRegistries.BLOCK
                                    .getValue(id)
                                    .defaultBlockState());
                })
                .collect(java.util.stream.Collectors.toMap(
                        (java.util.Map.Entry<BlockPos, String> entry) -> entry.getKey(),
                        (java.util.Map.Entry<BlockPos, String> entry) -> entry.getValue()));
        var leavesMatcher = BlockMatcher.parse("#minecraft:leaves").orElseThrow();
        Set<BlockPos> observedLeaves =
                resources == null ? Set.of() : resources.recentOrRemembered(c, leavesMatcher, evidenceRadius, 1024);
        Set<BlockPos> leaves = observedLeaves.stream()
                .filter(pos -> pos.distSqr(origin) <= (double) radius * radius)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        int limit = a.has("limit") ? a.get("limit").getAsInt() : 0;
        boolean collect = a.has("collect") && !a.get("collect").isJsonNull()
                ? a.get("collect").getAsBoolean()
                : fellCollectDrops.getAsBoolean();
        List<TreeFinder.TreeInfo> all = TreeFinder.findObserved(level, origin, observed, leaves, 1 + 8, true);
        List<TreeFinder.TreeInfo> found = all.stream()
                .filter(tree -> !treesOnly || tree.kind() != TreeFinder.Kind.LOG_STRUCTURE)
                .toList();
        if (found.isEmpty()) {
            if (treesOnly) {
                List<TreeFinder.TreeInfo> structures = all.stream()
                        .filter(tree -> tree.kind() == TreeFinder.Kind.LOG_STRUCTURE)
                        .toList();
                if (!structures.isEmpty()) {
                    TreeFinder.TreeInfo s = structures.get(0);
                    String where = origin.getX() + " " + origin.getY() + " " + origin.getZ();
                    throw new IllegalArgumentException("no tree within " + radius + " blocks of " + where
                            + "; the nearest " + s.type() + " is a log structure at "
                            + s.base().getX() + " "
                            + s.base().getY() + " " + s.base().getZ()
                            + "; use mine_blocks to take named blocks");
                }
            }
            return new FellTreeSearchTask(matcher, origin, radius, limit, collect);
        }
        return new FellTreeTask(found.get(0), logs, limit, collect);
    }

    private static MineBlockAtTask createMineBlockAt(CompanionEntity c, JsonObject args) {
        int x = args.get("x").getAsInt();
        int y = args.get("y").getAsInt();
        int z = args.get("z").getAsInt();
        if (y < c.level().getMinY() || y > c.level().getMaxY()) {
            throw new IllegalArgumentException("y is outside the world ("
                    + c.level().getMinY() + ".." + c.level().getMaxY() + ")");
        }
        if (!c.level().hasChunkAt(x, z)) {
            throw new IllegalArgumentException("the chunk at " + x + " " + z + " is not loaded");
        }
        return new MineBlockAtTask(new BlockPos(x, y, z));
    }

    private static BlockMatcher parseBlockMatcher(String value) {
        return BlockMatcher.parse(value)
                .orElseThrow(() -> new IllegalArgumentException("unknown block or tag: " + value));
    }

    private static ToolMeta metadata(String name) {
        String hint = switch (name) {
            case "fell_tree" ->
                "- To chop a tree, call find_trees when the kind or the exact tree is unclear, then fell_tree. It climbs the trunk once and cuts the tree from the crown down, so the logs fall to the foot. limit takes the top N logs and leaves the trunk standing; it is a cap, not a shortcut. Pass collect:false only when speed matters more than the drops.\n"
                        + "- Chopping, mining and a tree search start around you: near is not needed when nobody is named. near \"me\" is only the player's own position. When the player points at a tree or a block, pass the coordinates they mean, not a moved search origin.";
            case "mine_blocks" ->
                "- Use mine_blocks for a kind of block (ore, stone, a named log type); use fell_tree when the player asks for a tree.\n"
                        + "- After placing blocks, mine_blocks with placed:true takes back only those blocks; it never touches an unplaced neighbour.";
            case "mine_block_at" ->
                "- Use mine_block_at when the owner points at one block: it mines that block and continues into its connected vein, then says how many it mined and what it took. Use mine_blocks to collect a number of blocks of a kind found around you; use fell_tree for a whole tree.";
            default -> throw new IllegalArgumentException("unknown gathering tool: " + name);
        };
        return new ToolMeta(name, ToolGroup.GATHERING, false, hint, List.of(), Cost.HIGH);
    }
}
