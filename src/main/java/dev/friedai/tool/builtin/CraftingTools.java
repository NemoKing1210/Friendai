package dev.friedai.tool.builtin;

import com.google.gson.JsonObject;
import dev.friedai.crafting.CraftPlan;
import dev.friedai.crafting.CraftPlanner;
import dev.friedai.crafting.CraftText;
import dev.friedai.crafting.Recipe;
import dev.friedai.crafting.Station;
import dev.friedai.entity.CompanionEntity;
import dev.friedai.task.builtin.CraftTask;
import dev.friedai.task.builtin.CraftTask.StationPolicy;
import dev.friedai.task.crafting.RecipeIndex;
import dev.friedai.task.crafting.Stations;
import dev.friedai.tool.SchemaBuilder;
import dev.friedai.tool.ToolRegistry;
import dev.friedai.tool.ToolResult;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;

/** Crafting: starting a craft and explaining a recipe before the companion commits to one. */
final class CraftingTools {
    /** How many alternative recipes {@code get_recipe} lists before summarising the rest. */
    private static final int RECIPES_SHOWN = 4;

    private CraftingTools() {}

    static void register(ToolRegistry<CompanionEntity> registry, List<TaskToolDef> defs) {
        ToolKit.add(registry, defs, new TaskToolDef("craft_item",
                "Craft an item from the companion's inventory, crafting intermediate items (planks, sticks, a crafting table) as needed. Uses a nearby crafting table, stonecutter or smithing table, or places its own. Call get_recipe first for anything complex.",
                SchemaBuilder.object()
                        .string("item", "Item id like minecraft:stick; tags are not accepted")
                        .integer("count", "How many to craft", 1, 64)
                        .enumeration("station", "What to do with a station the companion places itself", "keep", "take")
                        .required("item", "count")
                        .build(),
                (c, a) -> {
                    String item = itemId(a.get("item").getAsString());
                    int count = a.get("count").getAsInt();
                    return new CraftTask(item, count, policy(a));
                }, true));
        registry.register(ToolKit.instantTool("get_recipe",
                "Show how an item is crafted, which station it needs, and whether the companion can craft it now from its inventory.",
                SchemaBuilder.object()
                        .string("item", "Item id like minecraft:stick; tags are not accepted")
                        .integer("count", "How many to plan for", 1, 64)
                        .required("item")
                        .build(),
                (c, a) -> {
                    String item = itemId(a.get("item").getAsString());
                    int count = a.has("count") && !a.get("count").isJsonNull() ? a.get("count").getAsInt() : 1;
                    return ToolResult.ok(recipeText(c, item, count));
                }));
    }

    private static StationPolicy policy(JsonObject args) {
        if (!args.has("station") || args.get("station").isJsonNull()) {
            return StationPolicy.AUTO;
        }
        return switch (args.get("station").getAsString()) {
            case "keep" -> StationPolicy.KEEP;
            case "take" -> StationPolicy.TAKE;
            default -> throw new IllegalArgumentException(
                    "station must be keep or take: " + args.get("station").getAsString());
        };
    }

    private static String recipeText(CompanionEntity companion, String item, int count) {
        ServerLevel level = (ServerLevel) companion.level();
        RecipeIndex index = RecipeIndex.of(level);
        List<Recipe> recipes = index.producing(item);
        if (recipes.isEmpty()) {
            return item + " has no crafting, stonecutter or smithing recipe (it has to be found, mined or smelted)";
        }
        StringBuilder text = new StringBuilder();
        Set<Station> shownCooking = EnumSet.noneOf(Station.class);
        int shown = 0;
        int cursor = 0;
        while (cursor < recipes.size() && shown < RECIPES_SHOWN) {
            Recipe recipe = recipes.get(cursor++);
            if (recipe.station().isCooking() && !shownCooking.add(recipe.station())) {
                continue;
            }
            if (shown > 0) {
                text.append('\n');
            }
            text.append(CraftText.recipeText(recipe));
            shown++;
        }
        if (recipes.size() > cursor) {
            text.append('\n').append("+ ").append(recipes.size() - cursor).append(" more recipes");
        }
        CraftPlan plan = CraftPlanner.plan(item, count, inventoryMap(companion), index, Stations.available(level, companion));
        text.append('\n').append(CraftText.planText(plan, item, count));
        return text.toString();
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
        SimpleContainer inventory = companion.inventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            account(items, inventory.getItem(slot));
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
