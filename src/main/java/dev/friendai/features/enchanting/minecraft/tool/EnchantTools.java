package dev.friendai.features.enchanting.minecraft.tool;

import com.google.gson.JsonObject;
import dev.friendai.agent.tools.Cost;
import dev.friendai.agent.tools.ToolGroup;
import dev.friendai.agent.tools.ToolMeta;
import dev.friendai.execution.tool.SchemaBuilder;
import dev.friendai.features.enchanting.minecraft.task.AnvilItemTask;
import dev.friendai.features.enchanting.minecraft.task.EnchantItemTask;
import dev.friendai.features.enchanting.minecraft.task.GrindstoneItemTask;
import dev.friendai.server.execution.tools.FeatureToolContributor;
import dev.friendai.server.execution.tools.FeatureToolRegistry;
import dev.friendai.server.execution.tools.TaskToolDefinition;
import dev.friendai.task.inventory.ItemMatcher;
import java.util.List;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

/** Skills that spend the companion's experience levels at vanilla menu blocks. */
public final class EnchantTools implements FeatureToolContributor {
    @Override
    public void register(FeatureToolRegistry registry) {
        registry.registerTask(new TaskToolDefinition(
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
                        parseItemMatcher(a.get("item").getAsString()),
                        a.get("option").getAsInt(),
                        optionalPos(a)),
                true,
                metadata("enchant_item")));
        registry.registerTask(new TaskToolDefinition(
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
                        parseItemMatcher(a.get("item").getAsString()),
                        a.has("with") && !a.get("with").isJsonNull()
                                ? parseItemMatcher(a.get("with").getAsString())
                                : null,
                        a.has("rename") && !a.get("rename").isJsonNull()
                                ? a.get("rename").getAsString()
                                : null,
                        optionalPos(a)),
                true,
                metadata("anvil_item")));
        registry.registerTask(new TaskToolDefinition(
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
                        parseItemMatcher(a.get("item").getAsString()),
                        a.has("with") && !a.get("with").isJsonNull()
                                ? parseItemMatcher(a.get("with").getAsString())
                                : null,
                        optionalPos(a)),
                true,
                metadata("grindstone_item")));
    }

    private static ToolMeta metadata(String name) {
        return new ToolMeta(name, ToolGroup.ENCHANT, false, "", List.of(), Cost.MEDIUM);
    }

    private static ItemMatcher parseItemMatcher(String idOrTag) {
        return ItemMatcher.parse(idOrTag)
                .orElseThrow(() -> new IllegalArgumentException("unknown item or tag: " + idOrTag));
    }

    /** All of x, y, z or none. */
    private static @Nullable BlockPos optionalPos(JsonObject args) {
        int present = 0;
        for (String name : new String[] {"x", "y", "z"}) {
            if (args.has(name) && !args.get(name).isJsonNull()) {
                present++;
            }
        }
        if (present == 0) {
            return null;
        }
        if (present != 3) {
            throw new IllegalArgumentException("give all of x, y, z or none");
        }
        return new BlockPos(
                args.get("x").getAsInt(),
                args.get("y").getAsInt(),
                args.get("z").getAsInt());
    }
}
