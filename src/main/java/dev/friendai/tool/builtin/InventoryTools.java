package dev.friendai.tool.builtin;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.tool.SchemaBuilder;
import dev.friendai.execution.tool.ToolRegistry;
import dev.friendai.execution.tool.ToolResult;
import dev.friendai.features.survival.HungerModel;
import dev.friendai.perception.PerceptionSnapshot;
import dev.friendai.task.builtin.DropItemsTask;
import dev.friendai.task.builtin.GiveItemsTask;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

/** What the companion carries: handing items over and reporting the inventory. */
final class InventoryTools {
    private InventoryTools() {}

    static void register(ToolRegistry<CompanionEntity> registry, List<TaskToolDef> defs) {
        ToolKit.add(
                registry,
                defs,
                new TaskToolDef(
                        "give_items",
                        "Walk to the owner and give them items from the companion's inventory.",
                        SchemaBuilder.object()
                                .string("item", "Item id like minecraft:oak_log, or a tag like #minecraft:planks")
                                .integer("count", "How many items to give", 1, 64)
                                .required("item", "count")
                                .build(),
                        (c, a) -> new GiveItemsTask(
                                ToolKit.parseItemMatcher(a.get("item").getAsString()),
                                a.get("count").getAsInt()),
                        true));
        ToolKit.add(
                registry,
                defs,
                new TaskToolDef(
                        "drop_items",
                        "Drop items from the companion's inventory on the ground at its feet.",
                        SchemaBuilder.object()
                                .string("item", "Item id like minecraft:cobblestone, or a tag like #minecraft:planks")
                                .integer("count", "How many items to drop", 1, 64)
                                .required("item", "count")
                                .build(),
                        (c, a) -> new DropItemsTask(
                                ToolKit.parseItemMatcher(a.get("item").getAsString()),
                                a.get("count").getAsInt()),
                        true));
        registry.register(ToolKit.instantTool("get_inventory", "List what the companion is carrying.", (c, a) -> {
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (int i = 0; i < c.inventory().getContainerSize(); i++) {
                ItemStack s = c.inventory().getItem(i);
                if (!s.isEmpty()) {
                    String id = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
                    counts.merge(id, s.getCount(), Integer::sum);
                }
            }
            String equipment = c.equipmentText() + "; hunger " + c.hunger().food() + '/' + HungerModel.MAX_FOOD;
            return counts.isEmpty()
                    ? ToolResult.ok("Inventory is empty\n" + equipment)
                    : ToolResult.ok("Inventory: " + PerceptionSnapshot.counts(counts, "") + "\n" + equipment);
        }));
    }
}
