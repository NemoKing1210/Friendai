package dev.friendai.tool.builtin;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.tool.SchemaBuilder;
import dev.friendai.execution.tool.ToolRegistry;
import dev.friendai.task.builtin.AnvilItemTask;
import dev.friendai.task.builtin.EnchantItemTask;
import dev.friendai.task.builtin.GrindstoneItemTask;
import java.util.List;

/** Skills that spend the companion's experience levels at vanilla menu blocks. */
final class EnchantTools {
    private EnchantTools() {}

    static void register(ToolRegistry<CompanionEntity> registry, List<TaskToolDef> defs) {
        ToolKit.add(
                registry,
                defs,
                new TaskToolDef(
                        "enchant_item",
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
        ToolKit.add(
                registry,
                defs,
                new TaskToolDef(
                        "anvil_item",
                        "Use an anvil: repair an item with its material, combine it with another item or enchanted book, or rename it. Costs experience levels; the result is kept in the inventory. Give 'with' to repair or combine, 'rename' to rename, or both.",
                        SchemaBuilder.object()
                                .string("item", "The item to work on, like minecraft:iron_pickaxe")
                                .string("with", "The second item: repair material, another item or an enchanted book")
                                .string("rename", "A new name for the item")
                                .integer("x", "Anvil x")
                                .integer("y", "Anvil y")
                                .integer("z", "Anvil z")
                                .required("item")
                                .build(),
                        (c, a) -> new AnvilItemTask(
                                ToolKit.parseItemMatcher(a.get("item").getAsString()),
                                a.has("with") && !a.get("with").isJsonNull()
                                        ? ToolKit.parseItemMatcher(a.get("with").getAsString())
                                        : null,
                                a.has("rename") && !a.get("rename").isJsonNull()
                                        ? a.get("rename").getAsString()
                                        : null,
                                ToolKit.optionalPos(a).orElse(null)),
                        true));
        ToolKit.add(
                registry,
                defs,
                new TaskToolDef(
                        "grindstone_item",
                        "Use a grindstone: strip the enchantments from an item (curses stay) and collect the experience that comes out, or merge two damaged items into one.",
                        SchemaBuilder.object()
                                .string("item", "The item to grind, like minecraft:diamond_sword")
                                .string("with", "A second item of the same kind to merge, optional")
                                .integer("x", "Grindstone x")
                                .integer("y", "Grindstone y")
                                .integer("z", "Grindstone z")
                                .required("item")
                                .build(),
                        (c, a) -> new GrindstoneItemTask(
                                ToolKit.parseItemMatcher(a.get("item").getAsString()),
                                a.has("with") && !a.get("with").isJsonNull()
                                        ? ToolKit.parseItemMatcher(a.get("with").getAsString())
                                        : null,
                                ToolKit.optionalPos(a).orElse(null)),
                        true));
    }
}
