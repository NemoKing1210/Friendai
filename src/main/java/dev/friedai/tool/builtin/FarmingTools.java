package dev.friedai.tool.builtin;

import com.google.gson.JsonObject;
import dev.friedai.entity.CompanionEntity;
import dev.friedai.task.builtin.BonemealTask;
import dev.friedai.task.builtin.FarmTask;
import dev.friedai.task.builtin.TendFarmTask;
import dev.friedai.task.farming.Crops;
import dev.friedai.tool.SchemaBuilder;
import dev.friedai.tool.ToolRegistry;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

/** Farming: one pass, farmer mode and bone meal. */
final class FarmingTools {
    private FarmingTools() {}

    static void register(ToolRegistry<CompanionEntity> registry, List<TaskToolDef> defs) {
        ToolKit.add(registry, defs, new TaskToolDef("farm",
                "Harvest ripe crops around the companion (or a point), replant them and sow empty farmland. With till=true also tills soil next to water.",
                SchemaBuilder.object()
                        .integer("radius", "Farm radius in blocks", 1, 16)
                        .integer("x", "Farm X coordinate")
                        .integer("y", "Farm Y coordinate")
                        .integer("z", "Farm Z coordinate")
                        .string("crop", "Seed item id to sow on empty farmland, like minecraft:carrot")
                        .bool("till", "Also till soil next to water")
                        .build(),
                (c, a) -> new FarmTask(center(c, a), radius(a, 8), crop(a), till(a)),
                true));
        ToolKit.add(registry, defs, new TaskToolDef("tend_farm",
                "Stay at a farm and keep it: harvest and replant every minute and put the harvest into a nearby chest, until told otherwise.",
                SchemaBuilder.object()
                        .integer("radius", "Farm radius in blocks", 1, 16)
                        .integer("x", "Farm X coordinate")
                        .integer("y", "Farm Y coordinate")
                        .integer("z", "Farm Z coordinate")
                        .build(),
                (c, a) -> new TendFarmTask(center(c, a), radius(a, 8)),
                false));
        ToolKit.add(registry, defs, new TaskToolDef("use_bonemeal",
                "Use bone meal from the inventory on growing crops at a point or around the companion.",
                SchemaBuilder.object()
                        .integer("x", "Crop X coordinate")
                        .integer("y", "Crop Y coordinate")
                        .integer("z", "Crop Z coordinate")
                        .integer("radius", "Search radius in blocks", 1, 8)
                        .integer("count", "How many bone meal to use", 1, 64)
                        .build(),
                FarmingTools::bonemeal,
                true));
    }

    private static BlockPos center(CompanionEntity c, JsonObject args) {
        return ToolKit.optionalPos(args).orElse(c.blockPosition());
    }

    private static int radius(JsonObject args, int fallback) {
        return args.has("radius") && !args.get("radius").isJsonNull() ? args.get("radius").getAsInt() : fallback;
    }

    private static boolean till(JsonObject args) {
        return args.has("till") && !args.get("till").isJsonNull() && args.get("till").getAsBoolean();
    }

    private static @Nullable Item crop(JsonObject args) {
        if (!args.has("crop") || args.get("crop").isJsonNull()) {
            return null;
        }
        Item item = item(args.get("crop").getAsString());
        if (!Crops.isPlantableSeed(item)) {
            throw new IllegalArgumentException(BuiltInRegistries.ITEM.getKey(item) + " is not something I can plant");
        }
        return item;
    }

    private static BonemealTask bonemeal(CompanionEntity c, JsonObject args) {
        Optional<BlockPos> target = ToolKit.optionalPos(args);
        int radius = radius(args, 4);
        int count = args.has("count") && !args.get("count").isJsonNull()
                ? args.get("count").getAsInt()
                : Math.max(1, Math.min(64, c.inventory().countItem(Items.BONE_MEAL)));
        return new BonemealTask(target.orElse(null), radius, count);
    }

    private static Item item(String text) {
        Identifier id = Identifier.tryParse(text);
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
            throw new IllegalArgumentException("unknown item: " + text);
        }
        return BuiltInRegistries.ITEM.getValue(id);
    }
}
