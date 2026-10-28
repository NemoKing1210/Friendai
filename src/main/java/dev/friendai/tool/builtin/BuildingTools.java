package dev.friendai.tool.builtin;

import com.google.gson.JsonObject;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.tool.SchemaBuilder;
import dev.friendai.execution.tool.ToolRegistry;
import dev.friendai.features.interaction.minecraft.task.UseBlockTask;
import dev.friendai.task.builtin.ClearAreaTask;
import dev.friendai.task.builtin.LightAreaTask;
import dev.friendai.task.builtin.PlaceBlockTask;
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
        ToolKit.add(
                registry,
                defs,
                new TaskToolDef(
                        "place_block",
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
                            return new PlaceBlockTask(
                                    blockItem, ToolKit.optionalPos(a).orElse(null));
                        },
                        true));
        ToolKit.add(
                registry,
                defs,
                new TaskToolDef(
                        "use_block",
                        "Use a door, trapdoor, fence gate, lever or button at the given coordinates. With want, only acts if the block is not already in that state.",
                        SchemaBuilder.object()
                                .integer("x", "Block X coordinate")
                                .integer("y", "Block Y coordinate")
                                .integer("z", "Block Z coordinate")
                                .enumeration(
                                        "want",
                                        "The state to reach, if the block has it",
                                        "open",
                                        "closed",
                                        "on",
                                        "off")
                                .required("x", "y", "z")
                                .build(),
                        (c, a) -> new UseBlockTask(
                                new BlockPos(
                                        a.get("x").getAsInt(),
                                        a.get("y").getAsInt(),
                                        a.get("z").getAsInt()),
                                a.has("want") && !a.get("want").isJsonNull()
                                        ? a.get("want").getAsString()
                                        : null),
                        true));
        ToolKit.add(
                registry,
                defs,
                new TaskToolDef(
                        "light_area",
                        "Place torches from the inventory where it is dark around the companion.",
                        SchemaBuilder.object()
                                .integer("radius", "How far around the companion to light up, in blocks", 1, 16)
                                .build(),
                        (c, a) -> new LightAreaTask(
                                a.has("radius") ? a.get("radius").getAsInt() : 8),
                        true));
        ToolKit.add(
                registry,
                defs,
                new TaskToolDef(
                        "clear_area",
                        "Clear vegetation, snow, water sources and optionally stray blocks around a point, leaving chests, lava and protected places alone.",
                        SchemaBuilder.object()
                                .integer("radius", "How far around the centre to clear, in blocks (default 6)", 1, 16)
                                .integer("x", "Centre X; give all of x, y, z or none for the companion")
                                .integer("y", "Centre Y")
                                .integer("z", "Centre Z")
                                .integer("depth", "How far below the centre to clear (default 1)", 0, 8)
                                .integer("height", "How far above the centre to clear (default 4)", 1, 16)
                                .bool("water", "Take water sources with an empty bucket (default true)")
                                .bool("blocks", "Also break solid blocks, not only vegetation and snow (default false)")
                                .build(),
                        (c, a) -> new ClearAreaTask(
                                ToolKit.optionalPos(a).orElse(c.blockPosition()),
                                intArg(a, "radius", 6),
                                intArg(a, "depth", 1),
                                intArg(a, "height", 4),
                                boolArg(a, "water", true),
                                boolArg(a, "blocks", false)),
                        true));
    }

    private static int intArg(JsonObject args, String name, int fallback) {
        return args.has(name) && !args.get(name).isJsonNull() ? args.get(name).getAsInt() : fallback;
    }

    private static boolean boolArg(JsonObject args, String name, boolean fallback) {
        return args.has(name) && !args.get(name).isJsonNull() ? args.get(name).getAsBoolean() : fallback;
    }
}
