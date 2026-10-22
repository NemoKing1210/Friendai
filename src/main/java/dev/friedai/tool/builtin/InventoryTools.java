package dev.friedai.tool.builtin;

import dev.friedai.entity.CompanionEntity;
import dev.friedai.perception.PerceptionSnapshot;
import dev.friedai.task.builtin.GiveItemsTask;
import dev.friedai.tool.SchemaBuilder;
import dev.friedai.tool.ToolRegistry;
import dev.friedai.tool.ToolResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

/** What the companion carries: handing items over and reporting the inventory. */
final class InventoryTools {
    private InventoryTools() {}

    static void register(ToolRegistry<CompanionEntity> registry, List<TaskToolDef> defs) {
        ToolKit.add(registry, defs, new TaskToolDef("give_items", "Walk to the owner and give them items from the companion's inventory.",
                SchemaBuilder.object()
                        .string("item", "Item id like minecraft:oak_log, or a tag like #minecraft:planks")
                        .integer("count", "How many items to give", 1, 64)
                        .required("item", "count")
                        .build(),
                (c, a) -> new GiveItemsTask(ToolKit.parseItemMatcher(a.get("item").getAsString()), a.get("count").getAsInt()), true));
        registry.register(ToolKit.instantTool("get_inventory", "List what the companion is carrying.", (c, a) -> {
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (int i = 0; i < c.inventory().getContainerSize(); i++) {
                ItemStack s = c.inventory().getItem(i);
                if (!s.isEmpty()) {
                    String id = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
                    counts.merge(id, s.getCount(), Integer::sum);
                }
            }
            return counts.isEmpty()
                    ? ToolResult.ok("Inventory is empty")
                    : ToolResult.ok("Inventory: " + PerceptionSnapshot.counts(counts, ""));
        }));
    }
}
