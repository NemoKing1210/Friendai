package dev.friendai.tool.builtin;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.task.builtin.CheckContainerTask;
import dev.friendai.task.builtin.StoreItemsTask;
import dev.friendai.task.builtin.TakeItemsTask;
import dev.friendai.task.inventory.ItemMatcher;
import dev.friendai.tool.SchemaBuilder;
import dev.friendai.tool.ToolRegistry;
import java.util.List;

/** Storage: moving items between the inventory and containers. */
final class StorageTools {
    private StorageTools() {}

    static void register(ToolRegistry<CompanionEntity> registry, List<TaskToolDef> defs) {
        ToolKit.add(registry, defs, new TaskToolDef("store_items",
                "Put items from the companion's inventory into a chest, barrel or other storage container. Without item, stores everything.",
                SchemaBuilder.object()
                        .string("item", "Item id like minecraft:cobblestone, or a tag like #minecraft:planks")
                        .integer("count", "How many items to store", 1, 2304)
                        .integer("x", "Container X coordinate")
                        .integer("y", "Container Y coordinate")
                        .integer("z", "Container Z coordinate")
                        .build(),
                (c, a) -> {
                    ItemMatcher matcher = a.has("item") && !a.get("item").isJsonNull()
                            ? ToolKit.parseItemMatcher(a.get("item").getAsString())
                            : null;
                    int count = a.has("count") && !a.get("count").isJsonNull()
                            ? a.get("count").getAsInt()
                            : Integer.MAX_VALUE;
                    return new StoreItemsTask(matcher, count, ToolKit.optionalPos(a).orElse(null));
                }, true));
        ToolKit.add(registry, defs, new TaskToolDef("take_items",
                "Take items from a chest, barrel or other storage container into the companion's inventory.",
                SchemaBuilder.object()
                        .string("item", "Item id like minecraft:iron_ingot, or a tag like #minecraft:planks")
                        .integer("count", "How many items to take", 1, 2304)
                        .integer("x", "Container X coordinate")
                        .integer("y", "Container Y coordinate")
                        .integer("z", "Container Z coordinate")
                        .required("item", "count")
                        .build(),
                (c, a) -> new TakeItemsTask(
                        ToolKit.parseItemMatcher(a.get("item").getAsString()),
                        a.get("count").getAsInt(),
                        ToolKit.optionalPos(a).orElse(null)),
                true));
        ToolKit.add(registry, defs, new TaskToolDef("check_container",
                "Walk to a chest, barrel or other storage container and report what is inside.",
                SchemaBuilder.object()
                        .integer("x", "Container X coordinate")
                        .integer("y", "Container Y coordinate")
                        .integer("z", "Container Z coordinate")
                        .build(),
                (c, a) -> new CheckContainerTask(ToolKit.optionalPos(a).orElse(null)),
                true));
    }
}
