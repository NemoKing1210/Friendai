package dev.friendai.tool.builtin;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.Gson;
import dev.friendai.core.FriendaiServerState;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.knowledge.ResourceQuery;
import dev.friendai.pathing.Pos;
import dev.friendai.task.obtain.ResourceResolver;
import dev.friendai.tool.AccessLevel;
import dev.friendai.tool.SchemaBuilder;
import dev.friendai.tool.ToolRegistry;
import dev.friendai.tool.ToolResult;
import net.minecraft.server.level.ServerLevel;

/** Bounded queries over the owner's remembered resource evidence. */
final class ResourceTools {
    private static final Gson GSON = new Gson();
    private static final int DEFAULT_LIMIT = 5;
    private static final int MAX_DISTANCE = 30_000_000;

    private ResourceTools() {}

    static void register(ToolRegistry<CompanionEntity> registry, java.util.List<TaskToolDef> defs) {
        registry.register(ToolKit.instantTool("known_resources",
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

    private static ToolResult query(CompanionEntity companion, JsonObject args) {
        if (!(companion.level() instanceof ServerLevel level)) return ToolResult.error("resource knowledge is unavailable");
        if (companion.ownerUuid() == null) return ToolResult.error("resource knowledge has no owner scope");
        String resource = args.get("resource").getAsString();
        ResourceResolver.Resolution resolution = ResourceResolver.resolve(level, resource);
        if (!resolution.executable() && resolution.reason().startsWith("unknown")) return ToolResult.error(resolution.reason());
        int limit = args.has("limit") ? Math.max(1, Math.min(20, args.get("limit").getAsInt())) : DEFAULT_LIMIT;
        int maxDistance = args.has("max_distance") ? Math.max(1, Math.min(MAX_DISTANCE, args.get("max_distance").getAsInt())) : MAX_DISTANCE;
        boolean includeStale = !args.has("include_stale") || args.get("include_stale").getAsBoolean();
        var origin = companion.blockPosition();
        var book = FriendaiServerState.get(level.getServer()).knowledge().book(companion.ownerUuid());
        ResourceQuery.Request request = new ResourceQuery.Request(resolution.blockIds(),
                level.dimension().identifier().toString(), new Pos(origin.getX(), origin.getY(), origin.getZ()),
                maxDistance, limit, includeStale);
        long now = level.getServer().overworld().getGameTime();
        var results = ResourceQuery.find(book, request, now);
        JsonObject response = new JsonObject();
        response.addProperty("resource", resource);
        response.addProperty("scope", level.dimension().identifier().toString());
        response.addProperty("executable", resolution.executable());
        response.addProperty("reason", resolution.reason());
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
            item.add("position", position(site.representative().x(), site.representative().y(), site.representative().z()));
            item.add("approach", position(site.approach().x(), site.approach().y(), site.approach().z()));
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
