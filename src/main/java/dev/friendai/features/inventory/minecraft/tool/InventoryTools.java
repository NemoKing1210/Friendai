package dev.friendai.features.inventory.minecraft.tool;

import com.google.gson.JsonObject;
import dev.friendai.agent.tools.ToolCatalog;
import dev.friendai.agent.tools.ToolMeta;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.tool.SchemaBuilder;
import dev.friendai.execution.tool.Tool;
import dev.friendai.execution.tool.ToolResult;
import dev.friendai.features.inventory.minecraft.task.DropItemsTask;
import dev.friendai.features.inventory.minecraft.task.EquipTask;
import dev.friendai.features.inventory.minecraft.task.GiveItemsTask;
import dev.friendai.features.inventory.minecraft.task.PickupItemsTask;
import dev.friendai.features.inventory.minecraft.task.ThrowItemTask;
import dev.friendai.features.navigation.minecraft.nav.EntityTargets;
import dev.friendai.features.survival.HungerModel;
import dev.friendai.perception.PerceptionSnapshot;
import dev.friendai.server.execution.tools.FeatureToolContributor;
import dev.friendai.server.execution.tools.FeatureToolRegistry;
import dev.friendai.server.execution.tools.TaskToolDefinition;
import dev.friendai.task.Task;
import dev.friendai.task.inventory.ItemMatcher;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Carrying, throwing, equipping and picking up items. */
public final class InventoryTools implements FeatureToolContributor {
    public InventoryTools() {}

    @Override
    public void register(FeatureToolRegistry registry) {
        registry.registerTask(task(
                "give_items",
                "Walk to the owner and give them items from the companion's inventory.",
                SchemaBuilder.object()
                        .string("item", "Item id like minecraft:oak_log, or a tag like #minecraft:planks")
                        .integer("count", "How many items to give", 1, 64)
                        .required("item", "count")
                        .build(),
                (c, a) -> new GiveItemsTask(
                        matcher(a.get("item").getAsString()), a.get("count").getAsInt())));
        registry.registerTask(task(
                "drop_items",
                "Drop items from the companion's inventory on the ground at its feet.",
                SchemaBuilder.object()
                        .string("item", "Item id like minecraft:cobblestone, or a tag like #minecraft:planks")
                        .integer("count", "How many items to drop", 1, 64)
                        .required("item", "count")
                        .build(),
                (c, a) -> new DropItemsTask(
                        matcher(a.get("item").getAsString()), a.get("count").getAsInt())));
        registry.registerTask(task(
                "pickup_items",
                "Pick up dropped items within the given radius.",
                SchemaBuilder.object()
                        .integer("radius", "Search radius in blocks", 1, 16)
                        .string("item", "Item id like minecraft:egg, or a tag; only these are picked up")
                        .bool("placed", "Pick up around the nearest block you placed instead of around yourself")
                        .required("radius")
                        .build(),
                (c, a) -> new PickupItemsTask(
                        a.get("radius").getAsInt(),
                        a.has("item") && !a.get("item").isJsonNull()
                                ? matcher(a.get("item").getAsString())
                                : null,
                        a.has("placed")
                                && !a.get("placed").isJsonNull()
                                && a.get("placed").getAsBoolean())));
        registry.registerTask(task(
                "equip_item",
                "Wear or hold an item from the inventory: armor goes to its slot, other items to the main hand unless a slot is given.",
                SchemaBuilder.object()
                        .string("item", "Item id like minecraft:iron_helmet")
                        .enumeration("slot", "Where to put it", "mainhand", "offhand", "head", "chest", "legs", "feet")
                        .required("item")
                        .build(),
                (c, a) -> new EquipTask(item(a.get("item").getAsString()), slot(a))));
        registry.registerTask(task(
                "throw_item",
                "Toss items toward a target or coordinates, or straight ahead. Use this only for a small number of items; use give_items to hand something to the owner.",
                SchemaBuilder.object()
                        .string("item", "Item id or tag to throw")
                        .integer("count", "How many to throw (1 to 64); default 1", 1, 64)
                        .string("target", "owner, a player's name, or an animal/mob type such as cow")
                        .number("x", "Aim at this X coordinate")
                        .number("y", "Aim at this Y coordinate")
                        .number("z", "Aim at this Z coordinate")
                        .required("item")
                        .build(),
                InventoryTools::throwItem));
        registry.registerTool(
                inventoryTool(
                        "get_inventory",
                        "List what the companion is carrying.",
                        SchemaBuilder.object().build(),
                        (c, a) -> {
                            Map<String, Integer> counts = new java.util.LinkedHashMap<>();
                            for (int i = 0; i < c.inventory().getContainerSize(); i++) {
                                ItemStack stack = c.inventory().getItem(i);
                                if (!stack.isEmpty()) {
                                    String id = BuiltInRegistries.ITEM
                                            .getKey(stack.getItem())
                                            .getPath();
                                    counts.merge(id, stack.getCount(), Integer::sum);
                                }
                            }
                            String equipment =
                                    c.equipmentText() + "; hunger " + c.hunger().food() + '/' + HungerModel.MAX_FOOD;
                            return counts.isEmpty()
                                    ? ToolResult.ok("Inventory is empty\n" + equipment)
                                    : ToolResult.ok(
                                            "Inventory: " + PerceptionSnapshot.counts(counts, "") + "\n" + equipment);
                        }),
                metadata("get_inventory"));
    }

    private static TaskToolDefinition task(
            String name, String description, JsonObject schema, TaskToolDefinition.Factory factory) {
        return new TaskToolDefinition(name, description, schema, factory, true, metadata(name));
    }

    private static Tool<CompanionEntity> inventoryTool(
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

    private static ToolMeta metadata(String name) {
        return ToolCatalog.defaults()
                .meta(name)
                .orElseThrow(() -> new IllegalArgumentException("missing tool metadata: " + name));
    }

    private static Task throwItem(CompanionEntity companion, JsonObject args) {
        ItemMatcher matcher = matcher(args.get("item").getAsString());
        int count = args.has("count") ? args.get("count").getAsInt() : 1;
        Supplier<Vec3> aim = null;
        String label = "";
        Entity targetEntity = null;
        if (args.has("target")) {
            targetEntity = named(companion, args.get("target").getAsString());
            if (targetEntity instanceof Piglin && matcher.test(new ItemStack(Items.GOLD_INGOT))) {
                throw new IllegalArgumentException(
                        "generic tools cannot initiate piglin barter; a dedicated explicit bounded barter order is required");
            }
            Entity exactTarget = targetEntity;
            aim = () -> exactTarget.isAlive() && !exactTarget.isRemoved() ? exactTarget.getEyePosition() : null;
            label = EntityTargets.label(targetEntity);
        } else if (args.has("x") && args.has("y") && args.has("z")) {
            Vec3 fixed = new Vec3(
                    args.get("x").getAsDouble(),
                    args.get("y").getAsDouble(),
                    args.get("z").getAsDouble());
            if (matcher.test(new ItemStack(Items.GOLD_INGOT))
                    && !companion
                            .level()
                            .getEntitiesOfClass(Piglin.class, AABB.ofSize(fixed, 2.0, 2.0, 2.0), Entity::isAlive)
                            .isEmpty()) {
                throw new IllegalArgumentException(
                        "generic tools cannot initiate piglin barter; a dedicated explicit bounded barter order is required");
            }
            aim = () -> fixed;
            label = (int) Math.floor(fixed.x) + " " + (int) Math.floor(fixed.y) + " " + (int) Math.floor(fixed.z);
        }
        return new ThrowItemTask(matcher, count, aim, label, targetEntity);
    }

    private static Entity named(CompanionEntity companion, String name) {
        return EntityTargets.find(companion, name)
                .orElseThrow(() -> new IllegalArgumentException("I can't see any " + name));
    }

    private static ItemMatcher matcher(String idOrTag) {
        return ItemMatcher.parse(idOrTag)
                .orElseThrow(() -> new IllegalArgumentException("unknown item or tag: " + idOrTag));
    }

    private static Item item(String text) {
        Identifier id = Identifier.tryParse(text);
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
            throw new IllegalArgumentException("unknown item: " + text);
        }
        return BuiltInRegistries.ITEM.getValue(id);
    }

    private static EquipmentSlot slot(JsonObject args) {
        if (!args.has("slot") || args.get("slot").isJsonNull()) return null;
        return switch (args.get("slot").getAsString()) {
            case "mainhand" -> EquipmentSlot.MAINHAND;
            case "offhand" -> EquipmentSlot.OFFHAND;
            case "head" -> EquipmentSlot.HEAD;
            case "chest" -> EquipmentSlot.CHEST;
            case "legs" -> EquipmentSlot.LEGS;
            case "feet" -> EquipmentSlot.FEET;
            default ->
                throw new IllegalArgumentException(
                        "unknown slot: " + args.get("slot").getAsString());
        };
    }

    @FunctionalInterface
    private interface Action {
        ToolResult run(CompanionEntity companion, JsonObject args);
    }
}
