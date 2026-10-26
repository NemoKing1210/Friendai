package dev.friendai.tool.builtin;

import com.google.gson.JsonObject;
import dev.friendai.crafting.obtain.ObtainPlan;
import dev.friendai.crafting.obtain.ObtainPlanner;
import dev.friendai.crafting.obtain.ObtainText;
import dev.friendai.crafting.obtain.WorldFacts;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.task.builtin.ObtainTask;
import dev.friendai.task.crafting.RecipeIndex;
import dev.friendai.task.obtain.DropIndex;
import dev.friendai.task.obtain.WorldScan;
import dev.friendai.tool.SchemaBuilder;
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
        ToolKit.add(registry, defs, new TaskToolDef("obtain_item",
                "Get an item by any means: take it from nearby chests, mine, hunt, farm, smelt and craft, making the tools needed on the way, and explore if a resource is not nearby. Re-plans after every step.",
                SchemaBuilder.object()
                        .string("item", "Item id like minecraft:iron_pickaxe; tags are not accepted")
                        .integer("count", "How many to get", 1, 64)
                        .bool("use_containers", "Allow taking items from nearby chests and barrels")
                        .bool("explore", "Allow searching further away and digging down when a resource is not nearby")
                        .required("item", "count")
                        .build(),
                (c, a) -> new ObtainTask(itemId(a.get("item").getAsString()), a.get("count").getAsInt(),
                        flag(a, "use_containers", true), flag(a, "explore", true)),
                true));
        registry.register(ToolKit.instantTool("plan_obtain",
                "Show how the companion would get an item from here, step by step, without doing it.",
                SchemaBuilder.object()
                        .string("item", "Item id like minecraft:iron_pickaxe; tags are not accepted")
                        .integer("count", "How many to get", 1, 64)
                        .bool("use_containers", "Allow taking items from nearby chests and barrels")
                        .bool("explore", "Allow searching further away and digging down when a resource is not nearby")
                        .required("item")
                        .build(),
                (c, a) -> {
                    String item = itemId(a.get("item").getAsString());
                    int count = a.has("count") && !a.get("count").isJsonNull() ? a.get("count").getAsInt() : 1;
                    return ToolResult.ok(planText(c, item, count,
                            flag(a, "use_containers", true), flag(a, "explore", true)));
                }));
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
