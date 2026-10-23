package dev.friendai.tool.builtin;

import com.google.gson.JsonObject;
import dev.friendai.crafting.Station;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.task.builtin.CollectSmeltedTask;
import dev.friendai.task.builtin.CookFoodTask;
import dev.friendai.task.builtin.CraftTask;
import dev.friendai.task.builtin.SmeltTask;
import dev.friendai.task.cooking.FuelPolicy;
import dev.friendai.task.inventory.ItemMatcher;
import dev.friendai.tool.SchemaBuilder;
import dev.friendai.tool.ToolRegistry;
import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/** Cooking with real fuel and time: smelting, food, and collecting finished jobs. */
final class CookingTools {
    /** How long a tool lets its task wait by the station. */
    private static final int WAIT_LIMIT_TICKS = 60 * 20;

    /** The biggest count the schema allows; {@code cook_food} means "all" when omitted. */
    private static final int MAX_COUNT = 256;

    private CookingTools() {}

    static void register(ToolRegistry<CompanionEntity> registry, List<TaskToolDef> defs) {
        ToolKit.add(registry, defs, new TaskToolDef("smelt_items",
                "Smelt or cook items in a furnace, blast furnace, smoker or campfire, with fuel from the inventory. Short jobs are waited out and collected; long jobs are loaded and you get an event when they are done. Use wait=true in plans that need the result right away.",
                SchemaBuilder.object()
                        .string("item", "Item id like minecraft:raw_iron, or a tag like #minecraft:iron_ores")
                        .integer("count", "How many to smelt", 1, MAX_COUNT)
                        .enumeration("station", "Force a station kind", "furnace", "blast_furnace", "smoker", "campfire")
                        .string("fuel", "Fuel item id or #tag; without it only cheap fuels are burned")
                        .bool("wait", "Wait for the result instead of leaving the job in the furnace")
                        .enumeration("keep_station", "What to do with a station the companion places itself", "keep", "take")
                        .required("item", "count")
                        .build(),
                (c, a) -> new SmeltTask(
                        ToolKit.parseItemMatcher(a.get("item").getAsString()),
                        a.get("count").getAsInt(),
                        station(a),
                        fuel(a),
                        wait(a),
                        policy(a),
                        WAIT_LIMIT_TICKS),
                true));
        ToolKit.add(registry, defs, new TaskToolDef("cook_food",
                "Cook the raw food the companion carries, using a smoker, campfire or furnace nearby.",
                SchemaBuilder.object()
                        .integer("count", "How many raw food items to cook; all when omitted", 1, MAX_COUNT)
                        .bool("wait", "Wait for the result instead of leaving the job in the furnace")
                        .enumeration("keep_station", "What to do with a station the companion places itself", "keep", "take")
                        .build(),
                (c, a) -> new CookFoodTask(
                        a.has("count") && !a.get("count").isJsonNull() ? a.get("count").getAsInt() : MAX_COUNT,
                        wait(a),
                        policy(a),
                        WAIT_LIMIT_TICKS),
                true));
        ToolKit.add(registry, defs, new TaskToolDef("collect_smelted",
                "Collect finished items from furnaces the companion loaded, or from the furnace at the given coordinates.",
                SchemaBuilder.object()
                        .integer("x", "Furnace x")
                        .integer("y", "Furnace y")
                        .integer("z", "Furnace z")
                        .build(),
                (c, a) -> new CollectSmeltedTask(ToolKit.optionalPos(a).orElse(null)),
                true));
    }

    private static @Nullable Station station(JsonObject args) {
        if (!args.has("station") || args.get("station").isJsonNull()) {
            return null;
        }
        return switch (args.get("station").getAsString()) {
            case "furnace" -> Station.FURNACE;
            case "blast_furnace" -> Station.BLAST_FURNACE;
            case "smoker" -> Station.SMOKER;
            case "campfire" -> Station.CAMPFIRE;
            default -> throw new IllegalArgumentException(
                    "station must be furnace, blast_furnace, smoker or campfire: " + args.get("station").getAsString());
        };
    }

    private static boolean wait(JsonObject args) {
        return args.has("wait") && !args.get("wait").isJsonNull() && args.get("wait").getAsBoolean();
    }

    private static CraftTask.StationPolicy policy(JsonObject args) {
        if (!args.has("keep_station") || args.get("keep_station").isJsonNull()) {
            return CraftTask.StationPolicy.AUTO;
        }
        return switch (args.get("keep_station").getAsString()) {
            case "keep" -> CraftTask.StationPolicy.KEEP;
            case "take" -> CraftTask.StationPolicy.TAKE;
            default -> throw new IllegalArgumentException(
                    "keep_station must be keep or take: " + args.get("keep_station").getAsString());
        };
    }

    /** An explicit fuel must at least sometimes be a fuel; the tag form is accepted when one member burns. */
    private static @Nullable ItemMatcher fuel(JsonObject args) {
        if (!args.has("fuel") || args.get("fuel").isJsonNull()) {
            return null;
        }
        String text = args.get("fuel").getAsString();
        ItemMatcher matcher = ToolKit.parseItemMatcher(text);
        if (!burns(matcher.label())) {
            throw new IllegalArgumentException(matcher.label() + " is not a fuel");
        }
        return matcher;
    }

    private static boolean burns(String label) {
        if (label.startsWith("#")) {
            TagKey<Item> tag = TagKey.create(Registries.ITEM, Identifier.parse(label.substring(1)));
            for (Holder<Item> holder : BuiltInRegistries.ITEM.getTagOrEmpty(tag)) {
                if (FuelPolicy.isFuel(new ItemStack(holder.value()))) {
                    return true;
                }
            }
            return false;
        }
        Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(label));
        return FuelPolicy.isFuel(new ItemStack(item));
    }
}
