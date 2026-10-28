package dev.friendai.tool.builtin;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.friendai.core.FriendaiServerState;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.SchemaBuilder;
import dev.friendai.execution.tool.ToolRegistry;
import dev.friendai.execution.tool.ToolResult;
import dev.friendai.features.navigation.pathing.Pos;
import dev.friendai.knowledge.ResourceKnowledgeText;
import dev.friendai.knowledge.ResourceQuery;
import dev.friendai.knowledge.search.SearchHint;
import dev.friendai.knowledge.search.SearchPlanner;
import dev.friendai.perception.LandmarkScanner;
import dev.friendai.task.builtin.SearchResourceTask;
import dev.friendai.task.obtain.ResourceResolver;
import dev.friendai.task.obtain.SearchTargets;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;

/** Bounded queries over the owner's remembered resource evidence. */
final class ResourceTools {
    private static final Gson GSON = new Gson();
    private static final int DEFAULT_LIMIT = 5;
    private static final int MAX_DISTANCE = 30_000_000;

    private ResourceTools() {}

    static void register(ToolRegistry<CompanionEntity> registry, java.util.List<TaskToolDef> defs) {
        JsonObject searchSchema = SchemaBuilder.object()
                .string("resource", "Block, block tag or item to search for")
                .enumeration("strategy", "Permitted search strategy", "auto", "surface", "cave", "mine")
                .enumeration("scope", "Search budget", "local", "expedition")
                .enumeration("heading", "Initial compass heading", "north", "east", "south", "west")
                .required("resource")
                .build();
        TaskToolDef search = new TaskToolDef(
                "search_resource",
                "Search for a resource using bounded, resource-specific strategies. #minecraft:logs, "
                        + "#minecraft:planks, oak_planks and stick resolve to wood and use a surface search; the "
                        + "companion inspects its surroundings before travelling. Unknown locations are not assumed.",
                searchSchema,
                (companion, args) -> new SearchResourceTask(
                        args.get("resource").getAsString(),
                        args.has("strategy") ? args.get("strategy").getAsString() : "auto",
                        args.has("scope") ? args.get("scope").getAsString() : "local",
                        args.has("heading") ? args.get("heading").getAsString() : null,
                        null),
                true,
                AccessLevel.TRUSTED,
                TaskToolDef.QueueTraits.DEFAULT,
                ResourceTools::callerSearch);
        ToolKit.addCaller(
                registry,
                defs,
                search,
                (companion, caller, args) -> ToolResult.error("resource search requires caller authorization"));

        JsonObject targetSchema = SchemaBuilder.object()
                .enumeration("kind", "What to find", "resource", "structure", "biome", "entity", "place")
                .string("target", "Block id/tag or item for resource; structure id; biome id; entity id; place name")
                .enumeration("strategy", "Permitted search strategy", "auto", "surface", "cave", "mine")
                .enumeration("scope", "Search budget", "local", "expedition")
                .enumeration("heading", "Initial compass heading", "north", "east", "south", "west")
                .required("kind", "target")
                .build();
        TaskToolDef findTarget = new TaskToolDef(
                "find_target",
                "Find anything, not only materials: a block family or item source (resource), a generated "
                        + "structure (village, fortress, monument, ancient_city, stronghold, trial_chambers, mineshaft, "
                        + "ruined_portal, ...), a biome, a living entity, or a place from your place book. Uses visible "
                        + "evidence and a bounded local or expedition budget; it never assumes an unseen location.",
                targetSchema,
                (companion, args) -> {
                    SearchTargets.Resolution resolved = SearchTargets.resolve(
                            companion,
                            args.get("kind").getAsString(),
                            args.get("target").getAsString());
                    if (!resolved.ok()) throw new IllegalArgumentException(resolved.error());
                    return new SearchResourceTask(
                            resolved.kind(),
                            args.has("strategy") ? args.get("strategy").getAsString() : "auto",
                            args.has("scope") ? args.get("scope").getAsString() : "local",
                            args.has("heading") ? args.get("heading").getAsString() : null,
                            null);
                },
                true,
                AccessLevel.TRUSTED,
                TaskToolDef.QueueTraits.DEFAULT,
                ResourceTools::callerFindTarget);
        ToolKit.addCaller(
                registry,
                defs,
                findTarget,
                (companion, caller, args) -> ToolResult.error("target search requires caller authorization"));

        registry.register(ToolKit.instantTool(
                "search_hint",
                "Steer the next search without naming a waypoint: a direction, landmark, biome or rough area with "
                        + "one line of evidence. The engine validates it (loaded, on land, not inside a protected "
                        + "place) and only adds a bounded bonus to matching frontiers; it expires in about two "
                        + "minutes. Use look_around or survey_area first, and never use it as a movement command.",
                SchemaBuilder.object()
                        .enumeration("direction", "Compass direction", "north", "east", "south", "west")
                        .string("landmark", "Landmark you saw, such as a village bell")
                        .string("biome", "Biome id, such as minecraft:forest")
                        .integer("x", "Rough area x", -30_000_000, 30_000_000)
                        .integer("z", "Rough area z", -30_000_000, 30_000_000)
                        .integer("radius", "Rough area radius in blocks", 8, 2_048)
                        .string("reason", "One-line evidence for this hint")
                        .bool("clear", "Clear the current hint instead of setting one")
                        .build(),
                AccessLevel.TRUSTED,
                ResourceTools::hint));
        registry.register(ToolKit.instantTool(
                "known_resources",
                "Query remembered resource sites for a block, block tag or item source. Results are historical evidence "
                        + "in the current dimension; they are not a guarantee that blocks or inventory remain there.",
                SchemaBuilder.object()
                        .string("resource", "Block id, block tag such as #minecraft:ores, or item id")
                        .integer("max_distance", "Maximum straight-line distance in blocks", 1, MAX_DISTANCE)
                        .integer("limit", "Maximum number of sites to return", 1, 20)
                        .bool("include_stale", "Include stale and depleted historical sites (default true)")
                        .required("resource")
                        .build(),
                AccessLevel.TRUSTED,
                (companion, args) -> query(companion, args)));
    }

    private static SearchResourceTask callerFindTarget(
            CompanionEntity companion, dev.friendai.execution.tool.Caller caller, JsonObject args) {
        String scope = args.has("scope") && !args.get("scope").isJsonNull()
                ? args.get("scope").getAsString()
                : "local";
        if (!scope.equals("local") && !scope.equals("expedition"))
            throw new IllegalArgumentException("scope must be local or expedition");
        if (scope.equals("expedition")) {
            var config = FriendaiServerState.get(((ServerLevel) companion.level()).getServer())
                    .config();
            if (caller.access() != AccessLevel.OWNER
                    || !config.tasks.resources.allowExpeditions
                    || !config.companion.chunkLoading.enabled)
                throw new IllegalArgumentException("expeditions are disabled or owner-only");
        }
        SearchTargets.Resolution resolved = SearchTargets.resolve(
                companion, args.get("kind").getAsString(), args.get("target").getAsString());
        if (!resolved.ok()) throw new IllegalArgumentException(resolved.error());
        return new SearchResourceTask(
                resolved.kind(),
                args.has("strategy") ? args.get("strategy").getAsString() : "auto",
                scope,
                args.has("heading") ? args.get("heading").getAsString() : null,
                caller);
    }

    private static ToolResult hint(CompanionEntity companion, JsonObject args) {
        var owner = companion.ownerUuid();
        if (owner == null) return ToolResult.error("search hints need an owner");
        var state = FriendaiServerState.get(((ServerLevel) companion.level()).getServer());
        long now = companion.level().getServer().overworld().getGameTime();
        if (args.has("clear") && args.get("clear").getAsBoolean()) {
            state.searchHints().clear(owner);
            return ToolResult.ok("search hint cleared");
        }
        SearchPlanner.Heading direction = null;
        if (args.has("direction") && !args.get("direction").isJsonNull()) {
            direction = SearchPlanner.Heading.valueOf(
                    args.get("direction").getAsString().toUpperCase(java.util.Locale.ROOT));
        }
        String landmark = string(args, "landmark");
        String biome = string(args, "biome");
        if (biome != null) {
            Identifier biomeId = Identifier.tryParse(biome);
            if (biomeId == null
                    || !((ServerLevel) companion.level())
                            .registryAccess()
                            .lookupOrThrow(Registries.BIOME)
                            .containsKey(ResourceKey.create(Registries.BIOME, biomeId))) {
                return ToolResult.error("unknown biome: " + biome);
            }
            biome = biomeId.toString();
        }
        String reason = string(args, "reason");
        Pos origin = new Pos(
                companion.blockPosition().getX(),
                companion.blockPosition().getY(),
                companion.blockPosition().getZ());
        Pos area = args.has("x") && args.has("z")
                ? new Pos(args.get("x").getAsInt(), origin.y(), args.get("z").getAsInt())
                : null;
        boolean explicitRadius = args.has("radius");
        int areaRadius = explicitRadius ? args.get("radius").getAsInt() : 64;
        if (area == null && direction == null && landmark != null) {
            String needle = landmark.toLowerCase(java.util.Locale.ROOT);
            for (var seen : LandmarkScanner.scan((ServerLevel) companion.level(), companion.blockPosition())) {
                if (!seen.kind().toLowerCase(java.util.Locale.ROOT).contains(needle)
                        && !seen.block().toLowerCase(java.util.Locale.ROOT).contains(needle)) continue;
                String[] parts = seen.position().split(" ");
                if (parts.length == 3) {
                    try {
                        area = new Pos(
                                Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
                        if (!explicitRadius) areaRadius = 16;
                    } catch (NumberFormatException malformed) {
                        // A malformed landmark position is treated as not seen.
                    }
                }
                if (area != null) break;
            }
            if (area == null) {
                return ToolResult.error("no landmark matching '" + landmark + "' is in view; use look_around first");
            }
        }
        SearchHint hint;
        try {
            hint = SearchHint.create(direction, landmark, biome, area, areaRadius, reason, now);
        } catch (IllegalArgumentException invalid) {
            return ToolResult.error(invalid.getMessage());
        }
        Pos destination = area;
        if (destination == null && direction != null) {
            int d = 64;
            destination = switch (direction) {
                case NORTH -> new Pos(origin.x(), origin.y(), origin.z() - d);
                case EAST -> new Pos(origin.x() + d, origin.y(), origin.z());
                case SOUTH -> new Pos(origin.x(), origin.y(), origin.z() + d);
                case WEST -> new Pos(origin.x() - d, origin.y(), origin.z());
            };
        }
        if (destination != null) {
            ServerLevel level = (ServerLevel) companion.level();
            SearchHint.Validation validation = hint.validate(
                    new SearchHint.ValidationFacts(
                            level.hasChunkAt(new BlockPos(destination.x(), destination.y(), destination.z())),
                            SearchTargets.landAt(level, destination),
                            !SearchTargets.protectedAt(companion, destination),
                            SearchTargets.inWorld(level, destination)),
                    now);
            if (!validation.accepted()) return ToolResult.error("search hint rejected: " + validation.reason());
        }
        state.searchHints().put(owner, hint);
        return ToolResult.ok("search hint set: " + hint.describe(now));
    }

    private static String string(JsonObject args, String field) {
        return args.has(field) && !args.get(field).isJsonNull()
                ? args.get(field).getAsString()
                : null;
    }

    private static SearchResourceTask callerSearch(
            CompanionEntity companion, dev.friendai.execution.tool.Caller caller, JsonObject args) {
        String scope = args.has("scope") && !args.get("scope").isJsonNull()
                ? args.get("scope").getAsString()
                : "local";
        if (!scope.equals("local") && !scope.equals("expedition"))
            throw new IllegalArgumentException("scope must be local or expedition");
        if (scope.equals("expedition")) {
            var config = FriendaiServerState.get(((ServerLevel) companion.level()).getServer())
                    .config();
            if (caller.access() != AccessLevel.OWNER
                    || !config.tasks.resources.allowExpeditions
                    || !config.companion.chunkLoading.enabled)
                throw new IllegalArgumentException("expeditions are disabled or owner-only");
        }
        return new SearchResourceTask(
                args.get("resource").getAsString(),
                args.has("strategy") ? args.get("strategy").getAsString() : "auto",
                scope,
                args.has("heading") ? args.get("heading").getAsString() : null,
                caller);
    }

    private static ToolResult query(CompanionEntity companion, JsonObject args) {
        if (!(companion.level() instanceof ServerLevel level))
            return ToolResult.error("resource knowledge is unavailable");
        if (companion.ownerUuid() == null) return ToolResult.error("resource knowledge has no owner scope");
        String resource = args.get("resource").getAsString();
        ResourceResolver.Resolution resolution = ResourceResolver.resolve(level, resource);
        if (!resolution.executable() && resolution.reason().startsWith("unknown"))
            return ToolResult.error(resolution.reason());
        int limit =
                args.has("limit") ? Math.max(1, Math.min(20, args.get("limit").getAsInt())) : DEFAULT_LIMIT;
        int maxDistance = args.has("max_distance")
                ? Math.max(1, Math.min(MAX_DISTANCE, args.get("max_distance").getAsInt()))
                : MAX_DISTANCE;
        boolean includeStale =
                !args.has("include_stale") || args.get("include_stale").getAsBoolean();
        var origin = companion.blockPosition();
        var book = FriendaiServerState.get(level.getServer()).knowledge().book(companion.ownerUuid());
        ResourceQuery.Request request = new ResourceQuery.Request(
                resolution.blockIds(),
                level.dimension().identifier().toString(),
                new Pos(origin.getX(), origin.getY(), origin.getZ()),
                maxDistance,
                limit,
                includeStale);
        long now = level.getServer().overworld().getGameTime();
        var results = ResourceQuery.find(book, request, now);
        JsonObject response = new JsonObject();
        response.addProperty("resource", resource);
        response.addProperty("scope", level.dimension().identifier().toString());
        response.addProperty("executable", resolution.executable());
        response.addProperty("reason", resolution.reason());
        response.addProperty("summary", ResourceKnowledgeText.summary(results, 600));
        JsonArray sites = new JsonArray();
        for (ResourceQuery.Result result : results) {
            var site = result.site();
            JsonObject item = new JsonObject();
            item.addProperty("site_id", site.id());
            item.addProperty("family", site.family());
            item.addProperty("availability", site.availability().name().toLowerCase(java.util.Locale.ROOT));
            item.addProperty("historical_count", result.lastObservedBlocks());
            item.addProperty("age_ticks", result.ageTicks());
            item.addProperty("distance_blocks", result.distanceBlocks());
            item.addProperty("approximate", site.approximate());
            item.addProperty("route_status", site.routeStatus().name().toLowerCase(java.util.Locale.ROOT));
            item.add(
                    "position",
                    position(
                            site.representative().x(),
                            site.representative().y(),
                            site.representative().z()));
            item.add(
                    "approach",
                    position(
                            site.approach().x(),
                            site.approach().y(),
                            site.approach().z()));
            sites.add(item);
        }
        response.add("sites", sites);
        return ToolResult.ok(GSON.toJson(response));
    }

    private static JsonObject position(int x, int y, int z) {
        JsonObject position = new JsonObject();
        position.addProperty("x", x);
        position.addProperty("y", y);
        position.addProperty("z", z);
        return position;
    }
}
