package dev.friendai.features.mining.minecraft.tool;

import com.google.gson.JsonObject;
import dev.friendai.agent.tools.Cost;
import dev.friendai.agent.tools.ToolGroup;
import dev.friendai.agent.tools.ToolMeta;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.execution.tool.SchemaBuilder;
import dev.friendai.execution.tool.Tool;
import dev.friendai.execution.tool.ToolResult;
import dev.friendai.features.mining.minecraft.mining.BlockMatcher;
import dev.friendai.features.mining.minecraft.mining.MiningRefusal;
import dev.friendai.features.mining.minecraft.mining.MiningToolView;
import dev.friendai.features.mining.minecraft.mining.TreeFinder;
import dev.friendai.features.mining.tree.TreeSummary;
import dev.friendai.features.mining.tree.TreeText;
import dev.friendai.features.places.minecraft.NearResolver;
import dev.friendai.features.places.minecraft.PlaceService;
import dev.friendai.perception.resource.ResourceCandidates;
import dev.friendai.server.execution.tools.FeatureToolContributor;
import dev.friendai.server.execution.tools.FeatureToolRegistry;
import dev.friendai.task.TaskContext;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.IntSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Queries over directly observed, remembered and visible blocks. */
public final class BlockInfoTools implements FeatureToolContributor {
    private static final double SPEAKER_VIEW_RANGE = 32.0;
    private static final double LOOK_RANGE = 64.0;

    private final IntSupplier mineRadius;
    private final IntSupplier treeRadius;
    private final Function<CompanionEntity, ResourceCandidates> resourceCandidates;
    private final PlaceService places;

    public BlockInfoTools(
            IntSupplier mineRadius,
            IntSupplier treeRadius,
            Function<CompanionEntity, ResourceCandidates> resourceCandidates) {
        this(mineRadius, treeRadius, resourceCandidates, PlaceService.unavailable());
    }

    public BlockInfoTools(
            IntSupplier mineRadius,
            IntSupplier treeRadius,
            Function<CompanionEntity, ResourceCandidates> resourceCandidates,
            PlaceService places) {
        this.mineRadius = Objects.requireNonNull(mineRadius);
        this.treeRadius = Objects.requireNonNull(treeRadius);
        this.resourceCandidates = resourceCandidates;
        this.places = Objects.requireNonNull(places);
    }

    @Override
    public void register(FeatureToolRegistry registry) {
        registry.registerTool(
                callerTool(
                        "find_trees",
                        "List trees recently seen or remembered with their kind, height, log count, distance and direction; "
                                + "a remembered tree is not guaranteed to still be there. A log structure has no leaves and "
                                + "is not felled automatically. Use it to choose a tree before fell_tree.",
                        SchemaBuilder.object()
                                .string("type", "Log block id like minecraft:oak_log, or a tag like #minecraft:logs")
                                .string(
                                        "near",
                                        "Search around the speaker (me, default), yourself (self), or a "
                                                + "remembered place (place:<name>)")
                                .integer(
                                        "x", "Block x coordinate to search around instead of near", -30000000, 30000000)
                                .integer("y", "Block y coordinate to search around instead of near", -64, 320)
                                .integer(
                                        "z", "Block z coordinate to search around instead of near", -30000000, 30000000)
                                .integer("radius", "Search radius in blocks", 4, 64)
                                .integer("limit", "How many trees to list", 1, 10)
                                .build(),
                        AccessLevel.TRUSTED,
                        (companion, caller, args) -> findTrees(companion, caller, args)),
                metadata("find_trees"));
        registry.registerTool(
                instantTool(
                        "find_blocks",
                        "List directly observed or remembered blocks of a given id or #tag with coordinates. Results are "
                                + "observations, not a survey of unseen terrain.",
                        SchemaBuilder.object()
                                .string("block", "Block id like minecraft:iron_ore, or a tag like #minecraft:logs")
                                .integer("radius", "Search radius in blocks", 1, 64)
                                .integer("limit", "How many blocks to list", 1, 10)
                                .required("block")
                                .build(),
                        (companion, args) -> findBlocks(companion, args)),
                metadata("find_blocks"));
        registry.registerTool(
                callerTool(
                        "describe_block",
                        "Facts about a block: id, hardness, whether it can be dug by hand or needs a proper tool "
                                + "(and whether you have one), plus the light level there. Omit x, y and z to describe the "
                                + "block the speaker is looking at.",
                        SchemaBuilder.object()
                                .integer("x", "Block x coordinate", -30000000, 30000000)
                                .integer("y", "Block y coordinate", -64, 320)
                                .integer("z", "Block z coordinate", -30000000, 30000000)
                                .build(),
                        AccessLevel.TRUSTED,
                        (companion, caller, args) -> describeBlock(companion, caller, args)),
                metadata("describe_block"));
    }

    private ToolResult findTrees(CompanionEntity companion, Caller caller, JsonObject args) {
        ResourceCandidates resources = resourceCandidates == null ? null : resourceCandidates.apply(companion);
        BlockPos origin = NearResolver.resolve(places, companion, caller, args);
        BlockMatcher matcher = args.has("type") && !args.get("type").isJsonNull()
                ? parseBlockMatcher(args.get("type").getAsString())
                : null;
        int radius = args.has("radius") ? args.get("radius").getAsInt() : treeRadius.getAsInt();
        int limit = args.has("limit") ? args.get("limit").getAsInt() : 5;
        // Observations are stored relative to the companion, while this query may be centred on the
        // speaking player. Expand only the evidence lookup; TreeFinder still applies the requested
        // radius around origin, and this does not inspect the world for unseen blocks.
        int evidenceRadius = Math.min(128, companion.blockPosition().distManhattan(origin) + radius);
        BlockMatcher logs = matcher == null ? parseBlockMatcher("#minecraft:logs") : matcher;
        Map<BlockPos, String> observedIds = resources == null
                ? Map.of()
                : resources.recentOrRememberedBlockIds(companion, evidenceRadius, 1024).entrySet().stream()
                        .filter(entry -> {
                            Identifier id = Identifier.tryParse(entry.getValue());
                            return id != null
                                    && BuiltInRegistries.BLOCK.containsKey(id)
                                    && logs.test(
                                            BuiltInRegistries.BLOCK.getValue(id).defaultBlockState());
                        })
                        .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        BlockMatcher leavesMatcher = parseBlockMatcher("#minecraft:leaves");
        Set<BlockPos> leaves = resources == null
                ? Set.of()
                : resources.recentOrRemembered(companion, leavesMatcher, evidenceRadius, 1024).stream()
                        .filter(pos -> pos.distSqr(origin) <= (double) radius * radius)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
        List<TreeFinder.TreeInfo> found =
                TreeFinder.findObserved((ServerLevel) companion.level(), origin, observedIds, leaves, limit);
        List<TreeSummary> trees = found.stream()
                .filter(tree -> tree.kind() != TreeFinder.Kind.LOG_STRUCTURE)
                .map(TreeFinder.TreeInfo::summary)
                .toList();
        List<TreeSummary> structures = found.stream()
                .filter(tree -> tree.kind() == TreeFinder.Kind.LOG_STRUCTURE)
                .map(TreeFinder.TreeInfo::summary)
                .toList();
        return ToolResult.ok(TreeText.toolListing(trees, structures, radius, "recently seen or remembered"));
    }

    private ToolResult findBlocks(CompanionEntity companion, JsonObject args) {
        ResourceCandidates resources = resourceCandidates == null ? null : resourceCandidates.apply(companion);
        BlockMatcher matcher = parseBlockMatcher(args.get("block").getAsString());
        int radius = args.has("radius") ? args.get("radius").getAsInt() : mineRadius.getAsInt();
        int limit = args.has("limit") ? args.get("limit").getAsInt() : 5;
        List<BlockPos> found =
                resources == null ? List.of() : resources.knownOrVisible(companion, matcher, radius, limit);
        if (found.isEmpty()) {
            return ToolResult.ok("no " + matcher.label() + " within " + radius + " blocks");
        }
        StringBuilder text =
                new StringBuilder(found.size() + " observed/remembered " + matcher.label() + " nearest first: ");
        TaskContext context = new TaskContext(companion, (ServerLevel) companion.level());
        for (int i = 0; i < found.size(); i++) {
            BlockPos pos = found.get(i);
            if (i > 0) {
                text.append(", ");
            }
            double distance = Math.sqrt(Vec3.atCenterOf(pos).distanceToSqr(companion.position()));
            text.append(pos.getX())
                    .append(' ')
                    .append(pos.getY())
                    .append(' ')
                    .append(pos.getZ())
                    .append(" (")
                    .append(String.format(Locale.ROOT, "%.1f", distance))
                    .append(" blocks)");
            MiningRefusal refusal = MiningRefusal.check(context, pos, false);
            if (refusal != null) {
                text.append(" (unmineable: ").append(refusal.cause()).append(')');
            }
        }
        return ToolResult.ok(text.toString());
    }

    private ToolResult describeBlock(CompanionEntity companion, Caller caller, JsonObject args) {
        ResourceCandidates resources = resourceCandidates == null ? null : resourceCandidates.apply(companion);
        Optional<BlockPos> asked = optionalPos(args);
        BlockPos pos = asked.orElse(null);
        if (pos == null) {
            pos = lookedAtBlock(companion, caller.uuid()).orElse(null);
        }
        if (pos == null) {
            return ToolResult.error("no block in view; pass x, y and z");
        }
        if (asked.isPresent() && (resources == null || !resources.currentlyVisible(companion, pos))) {
            Optional<String> remembered =
                    resources == null ? Optional.empty() : resources.rememberedBlockId(companion, pos);
            if (remembered.isEmpty()) {
                return ToolResult.ok("unknown at " + pos.getX() + " " + pos.getY() + " " + pos.getZ());
            }
            return ToolResult.ok("historically observed " + remembered.get() + " at " + pos.getX() + " " + pos.getY()
                    + " " + pos.getZ() + "; current contents are unknown until seen again");
        }
        ServerLevel level = (ServerLevel) companion.level();
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
        String facts = name + " at " + at + ": hardness " + number(hardness);
        if (!state.requiresCorrectToolForDrops()) {
            return ToolResult.ok(facts + ", can be dug by hand; light " + light);
        }
        String tool = MiningToolView.currentToolFor(companion, state).orElse(null);
        return ToolResult.ok(
                facts + ", needs a proper tool (you have " + (tool == null ? "none" : tool) + "); light " + light);
    }

    /** The visible speaker's looked-at block; an online speaker takes precedence over the owner. */
    private static Optional<BlockPos> lookedAtBlock(CompanionEntity companion, java.util.UUID principal) {
        if (!(companion.level() instanceof ServerLevel level)) {
            return Optional.empty();
        }
        ServerPlayer speaker =
                principal == null ? null : level.getServer().getPlayerList().getPlayer(principal);
        if (speaker == null) {
            speaker = companion.owner().orElse(null);
        }
        if (speaker == null
                || speaker.level() != level
                || companion.distanceTo(speaker) > SPEAKER_VIEW_RANGE
                || !companion.hasLineOfSight(speaker)) {
            return Optional.empty();
        }
        HitResult hit = speaker.pick(LOOK_RANGE, 1.0F, false);
        return hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK
                ? Optional.of(blockHit.getBlockPos())
                : Optional.empty();
    }

    private static String number(float value) {
        return value == Math.rint(value) ? String.valueOf((int) value) : String.format(Locale.ROOT, "%.1f", value);
    }

    private static Optional<BlockPos> optionalPos(JsonObject args) {
        int present = 0;
        for (String name : new String[] {"x", "y", "z"}) {
            if (args.has(name) && !args.get(name).isJsonNull()) {
                present++;
            }
        }
        if (present == 0) {
            return Optional.empty();
        }
        if (present != 3) {
            throw new IllegalArgumentException("give all of x, y, z or none");
        }
        return Optional.of(new BlockPos(
                args.get("x").getAsInt(),
                args.get("y").getAsInt(),
                args.get("z").getAsInt()));
    }

    private static BlockMatcher parseBlockMatcher(String value) {
        return BlockMatcher.parse(value)
                .orElseThrow(() -> new IllegalArgumentException("unknown block or tag: " + value));
    }

    private static Tool<CompanionEntity> instantTool(
            String name, String description, JsonObject schema, Action action) {
        return new Tool<>() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return description;
            }

            @Override
            public JsonObject schema() {
                return schema;
            }

            @Override
            public ToolResult execute(CompanionEntity companion, JsonObject args) {
                return action.run(companion, args);
            }
        };
    }

    private static Tool<CompanionEntity> callerTool(
            String name, String description, JsonObject schema, AccessLevel minAccess, CallerAction action) {
        return new Tool<>() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return description;
            }

            @Override
            public JsonObject schema() {
                return schema;
            }

            @Override
            public AccessLevel minAccess() {
                return minAccess;
            }

            @Override
            public ToolResult execute(CompanionEntity companion, JsonObject args) {
                return action.run(companion, new Caller(null, "", minAccess), args);
            }

            @Override
            public ToolResult execute(CompanionEntity companion, Caller caller, JsonObject args) {
                return action.run(companion, caller, args);
            }
        };
    }

    private static ToolMeta metadata(String name) {
        return switch (name) {
            case "find_trees", "find_blocks", "describe_block" ->
                new ToolMeta(name, ToolGroup.INFO, false, "", List.of(), Cost.LOW);
            default -> throw new IllegalArgumentException("unknown block information tool: " + name);
        };
    }

    private static String shortName(Identifier id) {
        return id.getNamespace().equals("minecraft") ? id.getPath() : id.toString();
    }

    @FunctionalInterface
    private interface Action {
        ToolResult run(CompanionEntity companion, JsonObject args);
    }

    @FunctionalInterface
    private interface CallerAction {
        ToolResult run(CompanionEntity companion, Caller caller, JsonObject args);
    }
}
