package dev.friendai.tool.builtin;

import com.google.gson.JsonObject;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.task.Task;
import dev.friendai.task.builtin.AttackPlayerTask;
import dev.friendai.task.builtin.EatTask;
import dev.friendai.task.builtin.EquipTask;
import dev.friendai.task.builtin.GetFoodTask;
import dev.friendai.task.builtin.HuntTask;
import dev.friendai.task.combat.CombatMode;
import dev.friendai.task.combat.Food;
import dev.friendai.task.combat.Threats;
import dev.friendai.tool.AccessLevel;
import dev.friendai.tool.Caller;
import dev.friendai.tool.SchemaBuilder;
import dev.friendai.tool.ToolRegistry;
import dev.friendai.tool.ToolResult;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/** Fighting, hunting, equipment and food. */
final class CombatTools {
    private CombatTools() {}

    static void register(ToolRegistry<CompanionEntity> registry, List<TaskToolDef> defs) {
        ToolKit.add(registry, defs, new TaskToolDef("attack",
                "Hunt and kill mobs of a type near the companion, then collect the drops. Not for villagers, golems, pets or players.",
                SchemaBuilder.object()
                        .string("target", "Entity type id like minecraft:cow, or a tag like #minecraft:skeletons")
                        .integer("count", "How many to hunt", 1, 16)
                        .integer("radius", "Search radius in blocks", 1, 32)
                        .required("target", "count")
                        .build(),
                (c, a) -> new HuntTask(
                        matcher(a.get("target").getAsString()),
                        targetLabel(a.get("target").getAsString()),
                        a.get("count").getAsInt(),
                        a.has("radius") && !a.get("radius").isJsonNull() ? a.get("radius").getAsInt() : 16),
                true));
        ToolKit.add(registry, defs, new TaskToolDef("attack_player",
                "Attack a player by name. Only for the owner, only where PvP is allowed.",
                SchemaBuilder.object()
                        .string("name", "Player name")
                        .required("name")
                        .build(),
                (c, a) -> attackPlayer(c, a.get("name").getAsString()),
                false,
                AccessLevel.OWNER));
        registry.register(ToolKit.instantTool("set_combat_mode",
                "Set how the companion fights: defensive (fight back and protect the owner), guard (also attack hostile mobs near the owner), passive (never attack, run away).",
                SchemaBuilder.object()
                        .enumeration("mode", "Combat mode", "defensive", "guard", "passive")
                        .required("mode")
                        .build(),
                (c, a) -> {
                    CombatMode mode = CombatMode.parse(a.get("mode").getAsString());
                    c.setCombatMode(mode);
                    return ToolResult.ok("combat mode is now " + mode.label());
                }));
        ToolKit.add(registry, defs, new TaskToolDef("equip_item",
                "Wear or hold an item from the inventory: armor goes to its slot, other items to the main hand unless a slot is given.",
                SchemaBuilder.object()
                        .string("item", "Item id like minecraft:iron_helmet")
                        .enumeration("slot", "Where to put it", "mainhand", "offhand", "head", "chest", "legs", "feet")
                        .required("item")
                        .build(),
                (c, a) -> new EquipTask(item(a.get("item").getAsString()), slot(a)),
                true));
        ToolKit.addCaller(registry, defs, new TaskToolDef("eat",
                "Eat food from the inventory to heal. Without item, picks the best safe food. When the owner names a food you normally refuse, eating it is their permission: it is remembered.",
                SchemaBuilder.object()
                        .string("item", "Food item id like minecraft:bread")
                        .build(),
                (c, a) -> new EatTask(optionalItem(a)),
                true), CombatTools::eat);
        ToolKit.add(registry, defs, new TaskToolDef("get_food",
                "Get food the most efficient way: cook what I carry, take it from chests, bake bread, harvest, hunt or fish.",
                SchemaBuilder.object()
                        .integer("count", "How many food items to get (default 4)", 1, 16)
                        .build(),
                (c, a) -> new GetFoodTask(a.has("count") && !a.get("count").isJsonNull()
                        ? a.get("count").getAsInt()
                        : 4),
                true));
        registry.register(ToolKit.instantTool("allow_food",
                "Owner only. Let the companion eat a food it normally refuses, such as rotten flesh, or one it keeps for emergencies. The rule is remembered across sessions.",
                SchemaBuilder.object()
                        .string("item", "Food item id like minecraft:rotten_flesh")
                        .required("item")
                        .build(),
                AccessLevel.OWNER,
                (c, a) -> setFoodRule(c, a.get("item").getAsString(), true)));
        registry.register(ToolKit.instantTool("forbid_food",
                "Owner only. Never let the companion eat a food, even one it would otherwise eat. The rule is remembered across sessions.",
                SchemaBuilder.object()
                        .string("item", "Food item id like minecraft:bread")
                        .required("item")
                        .build(),
                AccessLevel.OWNER,
                (c, a) -> setFoodRule(c, a.get("item").getAsString(), false)));
        registry.register(ToolKit.instantTool("reset_food_rule",
                "Owner only. Drop the owner's rule for one food, so the default applies again.",
                SchemaBuilder.object()
                        .string("item", "Food item id like minecraft:rotten_flesh")
                        .required("item")
                        .build(),
                AccessLevel.OWNER,
                (c, a) -> resetFoodRule(c, a.get("item").getAsString())));
        registry.register(ToolKit.instantTool("get_food_rules",
                "List the foods the owner allowed or forbade, the foods refused by default, and the ones kept for emergencies.",
                (c, a) -> describeFoodRules(c)));
    }

    /**
     * Starts the eat task, and when the owner names a food the companion would refuse, treats it as the
     * owner's permission and remembers it. A trusted player's request grants nothing.
     */
    private static ToolResult eat(CompanionEntity companion, Caller caller, JsonObject args) {
        Item food = optionalItem(args);
        String note = "";
        if (food != null && caller != null && caller.access() == AccessLevel.OWNER) {
            ItemStack stack = food.getDefaultInstance();
            String id = BuiltInRegistries.ITEM.getKey(food).toString();
            if (stack.get(DataComponents.FOOD) != null
                    && !Food.isSafe(companion, stack, true)
                    && companion.setFoodRule(id, true)) {
                note = "the owner allowed " + id + " from now on; ";
            }
        }
        EatTask task = new EatTask(food);
        int taskId = companion.taskRunner().start(task, "eat");
        return ToolResult.ok(note + "task #" + taskId + " started: " + task.describe());
    }

    private static @Nullable Item optionalItem(JsonObject args) {
        return args.has("item") && !args.get("item").isJsonNull() ? item(args.get("item").getAsString()) : null;
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
        if (!companion.clearFoodRule(id)) {
            return ToolResult.ok("I had no rule for " + id + "; the default applies.");
        }
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

    private static Item foodItem(String text) {
        Item food = item(text);
        if (food.getDefaultInstance().get(DataComponents.FOOD) == null) {
            throw new IllegalArgumentException(text + " is not food");
        }
        return food;
    }

    private static Task attackPlayer(CompanionEntity companion, String name) {
        if (!(companion.level() instanceof ServerLevel level)) {
            throw new IllegalArgumentException(name + " is not here");
        }
        ServerPlayer target = level.getServer().getPlayerList().getPlayerByName(name);
        if (target == null || target.level() != level) {
            throw new IllegalArgumentException(name + " is not here");
        }
        ServerPlayer owner = companion.owner().orElse(null);
        if (owner != null && owner.getUUID().equals(target.getUUID())) {
            throw new IllegalArgumentException("I won't attack you");
        }
        if (!Threats.mayFightPlayer(companion, target)) {
            throw new IllegalArgumentException(
                    level.isPvpAllowed() ? "I can't hurt " + name + " here" : "PvP is off here");
        }
        return new AttackPlayerTask(target.getUUID(), name);
    }

    private static Predicate<EntityType<?>> matcher(String text) {
        if (text.startsWith("#")) {
            Identifier id = Identifier.tryParse(text.substring(1));
            if (id == null) {
                throw new IllegalArgumentException("unknown mob: " + text);
            }
            TagKey<EntityType<?>> tag = TagKey.create(Registries.ENTITY_TYPE, id);
            return type -> type.builtInRegistryHolder().is(tag);
        }
        EntityType<?> type = entityType(text);
        if (type == EntityTypes.PLAYER) {
            throw new IllegalArgumentException("use attack_player for players");
        }
        if (type == EntityTypes.VILLAGER || type == EntityTypes.WANDERING_TRADER
                || type == EntityTypes.IRON_GOLEM || type == EntityTypes.SNOW_GOLEM
                || type == FriendaiEntities.COMPANION) {
            throw new IllegalArgumentException("I won't attack " + BuiltInRegistries.ENTITY_TYPE.getKey(type));
        }
        return candidate -> candidate == type;
    }

    private static String targetLabel(String text) {
        if (text.startsWith("#")) {
            Identifier id = Identifier.tryParse(text.substring(1));
            return "#" + (id == null ? text.substring(1) : id);
        }
        return BuiltInRegistries.ENTITY_TYPE.getKey(entityType(text)).toString();
    }

    private static EntityType<?> entityType(String text) {
        Identifier id = Identifier.tryParse(text);
        if (id == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(id)) {
            throw new IllegalArgumentException("unknown mob: " + text);
        }
        return BuiltInRegistries.ENTITY_TYPE.getValue(id);
    }

    private static Item item(String text) {
        Identifier id = Identifier.tryParse(text);
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
            throw new IllegalArgumentException("unknown item: " + text);
        }
        return BuiltInRegistries.ITEM.getValue(id);
    }

    private static @Nullable EquipmentSlot slot(JsonObject args) {
        if (!args.has("slot") || args.get("slot").isJsonNull()) {
            return null;
        }
        return switch (args.get("slot").getAsString()) {
            case "mainhand" -> EquipmentSlot.MAINHAND;
            case "offhand" -> EquipmentSlot.OFFHAND;
            case "head" -> EquipmentSlot.HEAD;
            case "chest" -> EquipmentSlot.CHEST;
            case "legs" -> EquipmentSlot.LEGS;
            case "feet" -> EquipmentSlot.FEET;
            default -> throw new IllegalArgumentException("unknown slot: " + args.get("slot").getAsString());
        };
    }
}
