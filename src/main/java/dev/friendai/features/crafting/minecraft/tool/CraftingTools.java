package dev.friendai.features.crafting.minecraft.tool;

import com.google.gson.JsonObject;
import dev.friendai.agent.tools.Cost;
import dev.friendai.agent.tools.ToolGroup;
import dev.friendai.agent.tools.ToolMeta;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.tool.SchemaBuilder;
import dev.friendai.execution.tool.Tool;
import dev.friendai.execution.tool.ToolResult;
import dev.friendai.features.crafting.CraftPlan;
import dev.friendai.features.crafting.CraftPlanner;
import dev.friendai.features.crafting.CraftText;
import dev.friendai.features.crafting.Recipe;
import dev.friendai.features.crafting.Station;
import dev.friendai.features.crafting.StationPolicy;
import dev.friendai.features.crafting.minecraft.RecipeIndex;
import dev.friendai.features.crafting.minecraft.Stations;
import dev.friendai.features.crafting.minecraft.task.CraftTask;
import dev.friendai.server.execution.tools.FeatureToolContributor;
import dev.friendai.server.execution.tools.FeatureToolRegistry;
import dev.friendai.server.execution.tools.TaskToolDefinition;
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
public final class CraftingTools implements FeatureToolContributor {
    /** How many alternative recipes {@code get_recipe} lists before summarising the rest. */
    private static final int RECIPES_SHOWN = 4;

    public CraftingTools() {}

    @Override
    public void register(FeatureToolRegistry registry) {
        registry.registerTask(new TaskToolDefinition(
                "craft_item",
                "Craft an item from the companion's inventory, crafting intermediate items (planks, sticks, a crafting table) as needed. Uses a nearby crafting table, stonecutter or smithing table, or places its own. Call get_recipe first for anything complex.",
                SchemaBuilder.object()
                        .string("item", "Item id like minecraft:stick; tags are not accepted")
                        .integer("count", "How many to craft; ones already carried do not count", 1, 64)
                        .enumeration("station", "What to do with a station the companion places itself", "keep", "take")
                        .required("item", "count")
                        .build(),
                (c, a) -> {
                    String item = itemId(a.get("item").getAsString());
                    int count = a.get("count").getAsInt();
                    return new CraftTask(item, count, policy(a));
                },
                true,
                TaskToolDefinition.QueueTraits.DEFAULT,
                metadata("craft_item")));
        registry.registerTool(
                new Tool<>() {
                    @Override
                    public String name() {
                        return "get_recipe";
                    }

                    @Override
                    public String description() {
                        return "Show how an item is crafted, which station it needs, and whether the companion can craft it now from its inventory.";
                    }

                    @Override
                    public JsonObject schema() {
                        return SchemaBuilder.object()
                                .string("item", "Item id like minecraft:stick; tags are not accepted")
                                .integer("count", "How many to plan for", 1, 64)
                                .required("item")
                                .build();
                    }

                    @Override
                    public ToolResult execute(CompanionEntity c, JsonObject a) {
                        String item = itemId(a.get("item").getAsString());
                        int count = a.has("count") && !a.get("count").isJsonNull()
                                ? a.get("count").getAsInt()
                                : 1;
                        return ToolResult.ok(recipeText(c, item, count));
                    }
                },
                metadata("get_recipe"));
    }

    private static ToolMeta metadata(String name) {
        return new ToolMeta(name, ToolGroup.CRAFTING, false, "", List.of(), Cost.MEDIUM);
    }

    private static StationPolicy policy(JsonObject args) {
        if (!args.has("station") || args.get("station").isJsonNull()) {
            return StationPolicy.AUTO;
        }
        return switch (args.get("station").getAsString()) {
            case "keep" -> StationPolicy.KEEP;
            case "take" -> StationPolicy.TAKE;
            default ->
                throw new IllegalArgumentException(
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
        Map<String, Integer> items = inventoryMap(companion);
        CraftPlan plan = CraftPlanner.planNew(item, count, items, index, Stations.available(level, companion));
        text.append('\n').append(CraftText.planText(plan, item, count));
        int carried = items.getOrDefault(item, 0);
        if (carried > 0) {
            text.append('\n')
                    .append("already carries ")
                    .append(carried)
                    .append(" × ")
                    .append(item);
        }
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
