package dev.friendai.tool.builtin;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.task.builtin.EnchantItemTask;
import dev.friendai.tool.SchemaBuilder;
import dev.friendai.tool.ToolRegistry;
import java.util.List;

/** Skills that spend the companion's experience levels at vanilla menu blocks. */
final class EnchantTools {
    private EnchantTools() {}

    static void register(ToolRegistry<CompanionEntity> registry, List<TaskToolDef> defs) {
        ToolKit.add(registry, defs, new TaskToolDef("enchant_item",
                "Enchant an item from the inventory at an enchanting table, paying lapis lazuli and experience levels. Option 1 is the cheapest and 3 the strongest; option N costs N lapis and N levels. Check your levels with get_inventory first.",
                SchemaBuilder.object()
                        .string("item", "Item id like minecraft:iron_sword, or a tag")
                        .integer("option", "Which of the three offers to take: 1, 2 or 3", 1, 3)
                        .integer("x", "Enchanting table x")
                        .integer("y", "Enchanting table y")
                        .integer("z", "Enchanting table z")
                        .required("item", "option")
                        .build(),
                (c, a) -> new EnchantItemTask(
                        ToolKit.parseItemMatcher(a.get("item").getAsString()),
                        a.get("option").getAsInt(),
                        ToolKit.optionalPos(a).orElse(null)),
                true));
    }
}
