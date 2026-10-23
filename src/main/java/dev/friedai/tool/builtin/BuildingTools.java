package dev.friedai.tool.builtin;

import dev.friedai.entity.CompanionEntity;
import dev.friedai.task.builtin.PlaceBlockTask;
import dev.friedai.tool.SchemaBuilder;
import dev.friedai.tool.ToolRegistry;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;

/** Building: placing blocks. */
final class BuildingTools {
    private BuildingTools() {}

    static void register(ToolRegistry<CompanionEntity> registry, List<TaskToolDef> defs) {
        ToolKit.add(registry, defs, new TaskToolDef("place_block",
                "Place a block from the companion's inventory at the given coordinates, or at a free spot nearby when no coordinates are given.",
                SchemaBuilder.object()
                        .string("block", "Block item id like minecraft:crafting_table")
                        .integer("x", "Block X coordinate")
                        .integer("y", "Block Y coordinate")
                        .integer("z", "Block Z coordinate")
                        .required("block")
                        .build(),
                (c, a) -> {
                    String text = a.get("block").getAsString();
                    Identifier id = Identifier.tryParse(text);
                    if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
                        throw new IllegalArgumentException("unknown item: " + text);
                    }
                    Item item = BuiltInRegistries.ITEM.getValue(id);
                    if (!(item instanceof BlockItem blockItem)) {
                        throw new IllegalArgumentException(id + " is not a block");
                    }
                    return new PlaceBlockTask(blockItem, ToolKit.optionalPos(a).orElse(null));
                }, true));
    }
}
