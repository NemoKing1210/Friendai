package dev.friendai.tool.builtin;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.task.builtin.LightAreaTask;
import dev.friendai.task.builtin.PlaceBlockTask;
import dev.friendai.task.builtin.UseBlockTask;
import dev.friendai.tool.SchemaBuilder;
import dev.friendai.tool.ToolRegistry;
import java.util.List;
import net.minecraft.core.BlockPos;
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
        ToolKit.add(registry, defs, new TaskToolDef("use_block",
                "Use a door, trapdoor, fence gate, lever or button at the given coordinates. With want, only acts if the block is not already in that state.",
                SchemaBuilder.object()
                        .integer("x", "Block X coordinate")
                        .integer("y", "Block Y coordinate")
                        .integer("z", "Block Z coordinate")
                        .enumeration("want", "The state to reach, if the block has it", "open", "closed", "on", "off")
                        .required("x", "y", "z")
                        .build(),
                (c, a) -> new UseBlockTask(
                        new BlockPos(a.get("x").getAsInt(), a.get("y").getAsInt(), a.get("z").getAsInt()),
                        a.has("want") && !a.get("want").isJsonNull() ? a.get("want").getAsString() : null),
                true));
        ToolKit.add(registry, defs, new TaskToolDef("light_area",
                "Place torches from the inventory where it is dark around the companion.",
                SchemaBuilder.object()
                        .integer("radius", "How far around the companion to light up, in blocks", 1, 16)
                        .build(),
                (c, a) -> new LightAreaTask(a.has("radius") ? a.get("radius").getAsInt() : 8),
                true));
    }
}
