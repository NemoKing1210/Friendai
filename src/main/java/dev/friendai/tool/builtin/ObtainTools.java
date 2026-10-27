package dev.friendai.tool.builtin;

import com.google.gson.JsonObject;
import dev.friendai.crafting.obtain.ObtainPlan;
import dev.friendai.crafting.obtain.ObtainPlanner;
import dev.friendai.crafting.obtain.ObtainText;
import dev.friendai.crafting.obtain.WorldFacts;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.core.FriendaiServerState;
import dev.friendai.task.builtin.ObtainTask;
import dev.friendai.task.crafting.RecipeIndex;
import dev.friendai.task.obtain.DropIndex;
import dev.friendai.task.obtain.WorldScan;
import dev.friendai.tool.SchemaBuilder;
import dev.friendai.tool.AccessLevel;
import dev.friendai.tool.Caller;
import dev.friendai.tool.ToolRegistry;
import dev.friendai.tool.ToolResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

/** Getting an item by any means, and explaining the plan before committing to it. */
final class ObtainTools {
    private ObtainTools() {}

    static void register(ToolRegistry<CompanionEntity> registry, List<TaskToolDef> defs) {
        JsonObject schema = SchemaBuilder.object()
                        .string("item", "Item id like minecraft:iron_pickaxe; tags are not accepted")
                        .integer("count", "How many to get", 1, 64)
                        .bool("use_containers", "Allow taking items from nearby chests and barrels")
                        .bool("explore", "Allow searching further away and digging down when a resource is not nearby")
                        .enumeration("scope", "Search budget", "local", "local", "expedition")
                        .required("item", "count")
                        .build();
        TaskToolDef obtain = new TaskToolDef("obtain_item",
                "Get an item by any means: take it from nearby chests, mine, hunt, farm, smelt and craft, making the tools needed on the way, and explore if a resource is not nearby. Uses any nearby log for planks and sticks; a specific wood is explored only when the request names it. Re-plans after every step.",
                schema,
                (c, a) -> new ObtainTask(itemId(a.get("item").getAsString()), a.get("count").getAsInt(),
                        flag(a, "use_containers", true), flag(a, "explore", true)),
                true, AccessLevel.TRUSTED, TaskToolDef.QueueTraits.DEFAULT,
                (c, caller, a) -> callerObtain(c, caller, a));
        ToolKit.addCaller(registry, defs, obtain, (c, caller, a) ->
                ToolResult.error("obtain requires caller authorization"));
        registry.register(ToolKit.instantTool("plan_obtain",
                "Show how the companion would get an item from here, step by step, without doing it. Uses any "
                        + "nearby log for planks and sticks; a specific wood is explored only when the request names it.",
                SchemaBuilder.object()
                        .string("item", "Item id like minecraft:iron_pickaxe; tags are not accepted")
                        .integer("count", "How many to get", 1, 64)
                        .bool("use_containers", "Allow taking items from nearby chests and barrels")
                        .bool("explore", "Allow searching further away and digging down when a resource is not nearby")
                        .enumeration("scope", "Search budget", "local", "local", "expedition")
                        .required("item")
                        .build(),
                (c, a) -> {
                    String item = itemId(a.get("item").getAsString());
                    int count = a.has("count") && !a.get("count").isJsonNull() ? a.get("count").getAsInt() : 1;
                    String result = planText(c, item, count, flag(a, "use_containers", true), flag(a, "explore", true));
                    if (a.has("scope") && "expedition".equals(a.get("scope").getAsString())) {
                        var policy = FriendaiServerState.get(((ServerLevel) c.level()).getServer()).config().tasks.resources;
                        result += "; expedition search policy: " + policy.expeditionRadius + " blocks, "
                                + policy.expeditionTicks + " ticks, owner only and disabled unless enabled by the server";
                    }
                    return ToolResult.ok(result);
                }));
    }

    private static ObtainTask callerObtain(CompanionEntity companion, Caller caller, JsonObject args) {
        String scope = args.has("scope") && !args.get("scope").isJsonNull()
                ? args.get("scope").getAsString() : "local";
        if (!scope.equals("local") && !scope.equals("expedition"))
            throw new IllegalArgumentException("scope must be local or expedition");
        var config = FriendaiServerState.get(((ServerLevel) companion.level()).getServer()).config();
        if (scope.equals("expedition") && (caller.access() != AccessLevel.OWNER
                || !config.tasks.resources.allowExpeditions || !config.companion.chunkLoading.enabled))
            throw new IllegalArgumentException("expeditions are disabled or owner-only");
        int radius = scope.equals("expedition") ? config.tasks.resources.expeditionRadius : config.tasks.resources.localRadius;
        int ticks = scope.equals("expedition") ? config.tasks.resources.expeditionTicks : config.tasks.resources.localTicks;
        return new ObtainTask(itemId(args.get("item").getAsString()), args.get("count").getAsInt(),
                flag(args, "use_containers", true), flag(args, "explore", true), ObtainTask.DEFAULT_MAX_STEPS,
                Math.max(ObtainTask.DEFAULT_MAX_TICKS, ticks), radius, ObtainTask.DEFAULT_EXPLORE_BLOCKS,
                ticks, scope, caller);
    }

    private static String planText(CompanionEntity companion, String item, int count, boolean useContainers,
            boolean explore) {
        ServerLevel level = (ServerLevel) companion.level();
        Map<String, Integer> inventory = inventoryMap(companion);
        WorldFacts world = WorldScan.scan(level, companion, WorldScan.BLOCK_RADIUS, useContainers, explore);
        ObtainPlan plan = ObtainPlanner.plan(item, count, inventory, RecipeIndex.of(level), DropIndex.of(level), world);
        if (!plan.actionable()) {
            return "can't get " + count + " " + item + " here: "
                    + ObtainText.missingText(plan.missing(), !explore);
        }
        return ObtainText.planText(plan, item, count, inventory);
    }

    private static boolean flag(JsonObject args, String name, boolean fallback) {
        return args.has(name) && !args.get(name).isJsonNull() ? args.get(name).getAsBoolean() : fallback;
    }

    private static String itemId(String text) {
        Identifier id = Identifier.tryParse(text.strip());
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
            throw new IllegalArgumentException("unknown item: " + text);
        }
        return id.toString();
    }

    private static Map<String, Integer> inventoryMap(CompanionEntity companion) {
        Map<String, Integer> items = new LinkedHashMap<>();
        for (int slot = 0; slot < companion.inventory().getContainerSize(); slot++) {
            account(items, companion.inventory().getItem(slot));
        }
        account(items, companion.getMainHandItem());
        return items;
    }

    private static void account(Map<String, Integer> items, ItemStack stack) {
        if (!stack.isEmpty()) {
            items.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(), Integer::sum);
        }
    }
}
