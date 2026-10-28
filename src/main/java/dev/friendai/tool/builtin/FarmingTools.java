package dev.friendai.tool.builtin;

import com.google.gson.JsonObject;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.tool.SchemaBuilder;
import dev.friendai.execution.tool.ToolRegistry;
import dev.friendai.execution.tool.ToolResult;
import dev.friendai.features.farming.FarmGoal;
import dev.friendai.features.farming.FarmPlan;
import dev.friendai.features.farming.FarmPlanner;
import dev.friendai.features.places.Place;
import dev.friendai.place.PlaceService;
import dev.friendai.task.TaskContext;
import dev.friendai.task.builtin.BonemealTask;
import dev.friendai.task.builtin.FarmTask;
import dev.friendai.task.builtin.TendFarmTask;
import dev.friendai.task.farming.CropIndex;
import dev.friendai.task.farming.Crops;
import dev.friendai.task.farming.FarmSnapshotBuilder;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

/** Farming: one pass, farmer mode and bone meal. */
final class FarmingTools {
    private FarmingTools() {}

    static void register(ToolRegistry<CompanionEntity> registry, List<TaskToolDef> defs) {
        ToolKit.add(
                registry,
                defs,
                new TaskToolDef(
                        "farm",
                        "Harvest ripe crops around the companion, a point or a remembered place, replant them and sow "
                                + "empty farmland. With till=true also tills soil next to water.",
                        SchemaBuilder.object()
                                .integer("radius", "Farm radius in blocks", 1, 16)
                                .integer("x", "Farm X coordinate")
                                .integer("y", "Farm Y coordinate")
                                .integer("z", "Farm Z coordinate")
                                .string(
                                        "place",
                                        "Remembered place to farm around; used only when x, y and z are absent")
                                .string("crop", "Seed item id to sow on empty farmland, like minecraft:carrot")
                                .bool("till", "Also till soil next to water")
                                .bool(
                                        "bonemeal",
                                        "Use bone meal from the inventory on growing crops after the pass's other work")
                                .build(),
                        (c, a) -> {
                            Optional<Place> place = place(c, a);
                            return new FarmTask(
                                    center(c, a, place), radius(a, place, 8), crop(a), till(a), flag(a, "bonemeal"));
                        },
                        true));
        registry.register(ToolKit.instantTool(
                "plan_farm",
                "Show what a farm pass would do here (harvest, replant, sow, till, bone meal, missing seeds) without doing it.",
                SchemaBuilder.object()
                        .integer("radius", "Farm radius in blocks", 1, 16)
                        .integer("x", "Farm X coordinate")
                        .integer("y", "Farm Y coordinate")
                        .integer("z", "Farm Z coordinate")
                        .string("crop", "Seed item id to sow on empty farmland, like minecraft:carrot")
                        .bool("till", "Also plan tilling soil next to water")
                        .bool("bonemeal", "Also plan bone meal on growing crops")
                        .build(),
                FarmingTools::planFarm));
        ToolKit.add(
                registry,
                defs,
                new TaskToolDef(
                        "tend_farm",
                        "Stay at a farm, or at a remembered place, and keep it: harvest and replant every minute and put "
                                + "the harvest into a nearby chest, until told otherwise.",
                        SchemaBuilder.object()
                                .integer("radius", "Farm radius in blocks", 1, 16)
                                .integer("x", "Farm X coordinate")
                                .integer("y", "Farm Y coordinate")
                                .integer("z", "Farm Z coordinate")
                                .string("place", "Remembered place to tend; used only when x, y and z are absent")
                                .build(),
                        (c, a) -> {
                            Optional<Place> place = place(c, a);
                            return new TendFarmTask(center(c, a, place), radius(a, place, 8));
                        },
                        false));
        ToolKit.add(
                registry,
                defs,
                new TaskToolDef(
                        "use_bonemeal",
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

    /** The place a farm call names, or empty; a place is ignored when x, y and z are given. */
    private static Optional<Place> place(CompanionEntity c, JsonObject args) {
        if (ToolKit.optionalPos(args).isPresent()
                || !args.has("place")
                || args.get("place").isJsonNull()) {
            return Optional.empty();
        }
        String name = args.get("place").getAsString();
        if (name.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(PlaceService.requireReachable(c, name, "farming"));
    }

    /** The centre of a pass: explicit coordinates, then a named place's anchor, then the companion. */
    private static BlockPos center(CompanionEntity c, JsonObject args, Optional<Place> place) {
        if (place.isPresent()) {
            var anchor = place.get().anchor();
            return new BlockPos(anchor.x(), anchor.y(), anchor.z());
        }
        return center(c, args);
    }

    /** A pass covers the named place's own radius, capped at the schema's largest farm. */
    private static int radius(JsonObject args, Optional<Place> place, int fallback) {
        if (args.has("radius") && !args.get("radius").isJsonNull()) {
            return args.get("radius").getAsInt();
        }
        return place.map(p -> Math.min(p.radius(), 16)).orElse(fallback);
    }

    private static int radius(JsonObject args, int fallback) {
        return args.has("radius") && !args.get("radius").isJsonNull()
                ? args.get("radius").getAsInt()
                : fallback;
    }

    private static boolean till(JsonObject args) {
        return flag(args, "till");
    }

    private static boolean flag(JsonObject args, String name) {
        return args.has(name) && !args.get(name).isJsonNull() && args.get(name).getAsBoolean();
    }

    /** The plan a pass would follow, without touching the world. */
    private static ToolResult planFarm(CompanionEntity c, JsonObject args) {
        Item crop = crop(args);
        int radius = Math.max(1, Math.min(16, radius(args, 8)));
        TaskContext context = new TaskContext(c, (ServerLevel) c.level());
        FarmPlan plan = FarmPlanner.plan(
                FarmSnapshotBuilder.build(context, center(c, args), radius, Set.of()),
                new FarmGoal(
                        crop == null
                                ? null
                                : BuiltInRegistries.ITEM.getKey(crop).toString(),
                        till(args),
                        flag(args, "bonemeal"),
                        0,
                        FarmGoal.DEFAULT_MAX_STEPS),
                CropIndex.of(context.level()));
        return ToolResult.ok(plan.describe());
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
