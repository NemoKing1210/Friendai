package dev.friendai.features.survival.minecraft.tool;

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
import dev.friendai.features.survival.minecraft.Food;
import dev.friendai.features.survival.minecraft.task.EatTask;
import dev.friendai.features.survival.minecraft.task.GetFoodTask;
import dev.friendai.features.survival.minecraft.task.SleepTask;
import dev.friendai.server.execution.tools.FeatureToolContributor;
import dev.friendai.server.execution.tools.FeatureToolRegistry;
import dev.friendai.server.execution.tools.TaskToolDefinition;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/** Self-care: eating, food gathering, sleeping and owner-managed food rules. */
public final class SurvivalTools implements FeatureToolContributor {
    @Override
    public void register(FeatureToolRegistry registry) {
        registry.registerTask(new TaskToolDefinition(
                "eat",
                "Eat food from the inventory to heal. Without item, picks the best safe food. When the owner names a food you normally refuse, eating it is their permission: it is remembered.",
                SchemaBuilder.object()
                        .string("item", "Food item id like minecraft:bread")
                        .build(),
                (c, a) -> new EatTask(optionalItem(a)),
                (c, caller, a) -> eatTask(c, caller, a),
                true,
                AccessLevel.TRUSTED,
                TaskToolDefinition.QueueTraits.DEFAULT,
                true,
                true,
                metadata("eat")));
        registry.registerTask(new TaskToolDefinition(
                "get_food",
                "Get food the most efficient way: cook what I carry, take it from chests, bake bread, harvest, hunt or fish.",
                SchemaBuilder.object()
                        .integer("count", "How many food items to get (default 4)", 1, 16)
                        .build(),
                (c, a) -> new GetFoodTask(
                        a.has("count") && !a.get("count").isJsonNull()
                                ? a.get("count").getAsInt()
                                : 4),
                true,
                metadata("get_food")));
        registry.registerTask(new TaskToolDefinition(
                "sleep",
                "Walk to a bed and sleep in it. By default you get up in the morning and refuse to sleep in the daytime; pass until_morning=false to sleep until told to get up.",
                SchemaBuilder.object()
                        .integer("x", "Bed X coordinate; the nearest bed when omitted")
                        .integer("y", "Bed Y coordinate")
                        .integer("z", "Bed Z coordinate")
                        .bool("until_morning", "Get up when the day comes; default true")
                        .build(),
                (c, a) -> new SleepTask(
                        optionalPos(a),
                        !a.has("until_morning") || a.get("until_morning").getAsBoolean()),
                true,
                metadata("sleep")));

        registerInstant(
                registry,
                "allow_food",
                "Owner only. Let the companion eat a food it normally refuses, such as rotten flesh, or one it keeps for emergencies. The rule is remembered across sessions.",
                SchemaBuilder.object()
                        .string("item", "Food item id like minecraft:rotten_flesh")
                        .required("item")
                        .build(),
                (c, a) -> setFoodRule(c, a.get("item").getAsString(), true));
        registerInstant(
                registry,
                "forbid_food",
                "Owner only. Never let the companion eat a food, even one it would otherwise eat. The rule is remembered across sessions.",
                SchemaBuilder.object()
                        .string("item", "Food item id like minecraft:bread")
                        .required("item")
                        .build(),
                (c, a) -> setFoodRule(c, a.get("item").getAsString(), false));
        registerInstant(
                registry,
                "reset_food_rule",
                "Owner only. Drop the owner's rule for one food, so the default applies again.",
                SchemaBuilder.object()
                        .string("item", "Food item id like minecraft:rotten_flesh")
                        .required("item")
                        .build(),
                (c, a) -> resetFoodRule(c, a.get("item").getAsString()));
        registerInstant(
                registry,
                "get_food_rules",
                "List the foods the owner allowed or forbade, the foods refused by default, and the ones kept for emergencies.",
                SchemaBuilder.object().build(),
                (c, a) -> describeFoodRules(c));
    }

    private static void registerInstant(
            FeatureToolRegistry registry, String name, String description, JsonObject schema, Action action) {
        registry.registerTool(
                new Tool<>() {
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
                        return AccessLevel.OWNER;
                    }

                    @Override
                    public ToolResult execute(CompanionEntity companion, JsonObject args) {
                        return action.run(companion, args);
                    }
                },
                metadata(name));
    }

    private static ToolMeta metadata(String name) {
        String hint = name.equals("get_food")
                ? "- When you are hungry with no food: if the owner is near, ask them; otherwise call get_food."
                : "";
        return new ToolMeta(name, ToolGroup.SURVIVAL, false, hint, List.of(), Cost.MEDIUM);
    }

    private static EatTask eatTask(CompanionEntity companion, @Nullable Caller caller, JsonObject args) {
        Item food = optionalItem(args);
        if (food != null && caller != null && caller.access() == AccessLevel.OWNER) {
            ItemStack stack = food.getDefaultInstance();
            String id = BuiltInRegistries.ITEM.getKey(food).toString();
            if (stack.get(DataComponents.FOOD) != null && !Food.isSafe(companion, stack, true)) {
                companion.setFoodRule(id, true);
            }
        }
        return new EatTask(food);
    }

    private static @Nullable Item optionalItem(JsonObject args) {
        return args.has("item") && !args.get("item").isJsonNull()
                ? item(args.get("item").getAsString())
                : null;
    }

    private static ToolResult setFoodRule(CompanionEntity companion, String itemText, boolean allowed) {
        Item food = foodItem(itemText);
        String id = BuiltInRegistries.ITEM.getKey(food).toString();
        if (!companion.setFoodRule(id, allowed)) {
            return ToolResult.error("I have no owner to remember food rules for.");
        }
        return ToolResult.ok((allowed ? "I may eat " : "I won't eat ") + id + " from now on; the rule is remembered.");
    }

    private static ToolResult resetFoodRule(CompanionEntity companion, String itemText) {
        Item food = foodItem(itemText);
        String id = BuiltInRegistries.ITEM.getKey(food).toString();
        if (!companion.clearFoodRule(id)) return ToolResult.ok("I had no rule for " + id + "; the default applies.");
        return ToolResult.ok("Rule for " + id + " removed; the default applies again.");
    }

    private static ToolResult describeFoodRules(CompanionEntity companion) {
        Map<String, Boolean> rules = companion.foodRules();
        List<String> allowed = rules.entrySet().stream()
                .filter(Map.Entry::getValue)
                .map(Map.Entry::getKey)
                .toList();
        List<String> forbidden = rules.entrySet().stream()
                .filter(entry -> !entry.getValue())
                .map(Map.Entry::getKey)
                .toList();
        return ToolResult.ok("Food rules. The owner allows: " + list(allowed) + ". The owner forbids: "
                + list(forbidden) + ". I refuse by default: " + String.join(", ", Food.defaultForbiddenIds())
                + ". I keep for emergencies: " + String.join(", ", Food.emergencyIds()) + ".");
    }

    private static String list(List<String> items) {
        return items.isEmpty() ? "nothing" : String.join(", ", items);
    }

    private static @Nullable BlockPos optionalPos(JsonObject args) {
        int present = 0;
        for (String name : List.of("x", "y", "z")) {
            if (args.has(name) && !args.get(name).isJsonNull()) present++;
        }
        if (present == 0) return null;
        if (present != 3) throw new IllegalArgumentException("give all of x, y, z or none");
        return new BlockPos(
                args.get("x").getAsInt(),
                args.get("y").getAsInt(),
                args.get("z").getAsInt());
    }

    private static Item foodItem(String text) {
        Item food = item(text);
        if (food.getDefaultInstance().get(DataComponents.FOOD) == null) {
            throw new IllegalArgumentException(text + " is not food");
        }
        return food;
    }

    private static Item item(String text) {
        Identifier id = Identifier.tryParse(text);
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id))
            throw new IllegalArgumentException("unknown item: " + text);
        return BuiltInRegistries.ITEM.getValue(id);
    }

    @FunctionalInterface
    private interface Action {
        ToolResult run(CompanionEntity companion, JsonObject args);
    }
}
