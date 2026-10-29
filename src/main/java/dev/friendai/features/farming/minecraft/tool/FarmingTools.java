package dev.friendai.features.farming.minecraft.tool;

import com.google.gson.JsonObject;
import dev.friendai.agent.tools.Cost;
import dev.friendai.agent.tools.ToolGroup;
import dev.friendai.agent.tools.ToolMeta;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.tool.SchemaBuilder;
import dev.friendai.execution.tool.Tool;
import dev.friendai.execution.tool.ToolResult;
import dev.friendai.features.farming.FarmGoal;
import dev.friendai.features.farming.FarmPlan;
import dev.friendai.features.farming.FarmPlanner;
import dev.friendai.features.farming.minecraft.CropIndex;
import dev.friendai.features.farming.minecraft.Crops;
import dev.friendai.features.farming.minecraft.FarmSnapshotBuilder;
import dev.friendai.features.farming.minecraft.task.BonemealTask;
import dev.friendai.features.farming.minecraft.task.FarmTask;
import dev.friendai.features.farming.minecraft.task.TendFarmTask;
import dev.friendai.features.places.Place;
import dev.friendai.features.places.minecraft.PlaceService;
import dev.friendai.server.execution.tools.FeatureToolContributor;
import dev.friendai.server.execution.tools.FeatureToolRegistry;
import dev.friendai.server.execution.tools.TaskToolDefinition;
import dev.friendai.task.TaskContext;
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
public final class FarmingTools implements FeatureToolContributor {
    private final PlaceService places;

    public FarmingTools() {
        this(PlaceService.unavailable());
    }

    public FarmingTools(PlaceService places) {
        this.places = java.util.Objects.requireNonNull(places);
    }

    @Override
    public void register(FeatureToolRegistry registry) {
        registry.registerTask(new TaskToolDefinition(
                "farm",
                "Harvest ripe crops around the companion, a point or a remembered place, replant them and sow "
                        + "empty farmland. With till=true also tills soil next to water.",
                SchemaBuilder.object()
                        .integer("radius", "Farm radius in blocks", 1, 16)
                        .integer("x", "Farm X coordinate")
                        .integer("y", "Farm Y coordinate")
                        .integer("z", "Farm Z coordinate")
                        .string("place", "Remembered place to farm around; used only when x, y and z are absent")
                        .string("crop", "Seed item id to sow on empty farmland, like minecraft:carrot")
                        .bool("till", "Also till soil next to water")
                        .bool(
                                "bonemeal",
                                "Use bone meal from the inventory on growing crops after the pass's other work")
                        .build(),
                (c, a) -> {
                    Optional<Place> place = place(places, c, a);
                    return new FarmTask(
                            center(c, a, place), radius(a, place, 8), crop(a), till(a), flag(a, "bonemeal"));
                },
                true,
                TaskToolDefinition.QueueTraits.DEFAULT,
                metadata("farm")));
        registry.registerTool(
                new Tool<>() {
                    @Override
                    public String name() {
                        return "plan_farm";
                    }

                    @Override
                    public String description() {
                        return "Show what a farm pass would do here (harvest, replant, sow, till, bone meal, missing seeds) without doing it.";
                    }

                    @Override
                    public JsonObject schema() {
                        return SchemaBuilder.object()
                                .integer("radius", "Farm radius in blocks", 1, 16)
                                .integer("x", "Farm X coordinate")
                                .integer("y", "Farm Y coordinate")
                                .integer("z", "Farm Z coordinate")
                                .string("crop", "Seed item id to sow on empty farmland, like minecraft:carrot")
                                .bool("till", "Also plan tilling soil next to water")
                                .bool("bonemeal", "Also plan bone meal on growing crops")
                                .build();
                    }

                    @Override
                    public ToolResult execute(CompanionEntity c, JsonObject a) {
                        return planFarm(c, a);
                    }
                },
                metadata("plan_farm"));
        registry.registerTask(new TaskToolDefinition(
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
                    Optional<Place> place = place(places, c, a);
                    return new TendFarmTask(center(c, a, place), radius(a, place, 8));
                },
                false,
                TaskToolDefinition.QueueTraits.DEFAULT,
                metadata("tend_farm")));
        registry.registerTask(new TaskToolDefinition(
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
                true,
                TaskToolDefinition.QueueTraits.DEFAULT,
                metadata("use_bonemeal")));
    }

    private static BlockPos center(CompanionEntity c, JsonObject args) {
        return optionalPos(args).orElse(c.blockPosition());
    }

    /** The place a farm call names, or empty; a place is ignored when x, y and z are given. */
    private static Optional<Place> place(PlaceService places, CompanionEntity c, JsonObject args) {
        if (optionalPos(args).isPresent()
                || !args.has("place")
                || args.get("place").isJsonNull()) {
            return Optional.empty();
        }
        String name = args.get("place").getAsString();
        if (name.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(places.requireReachable(c, name, "farming"));
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
        Optional<BlockPos> target = optionalPos(args);
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

    private static Optional<BlockPos> optionalPos(JsonObject args) {
        int present = 0;
        for (String name : List.of("x", "y", "z")) {
            if (args.has(name) && !args.get(name).isJsonNull()) present++;
        }
        if (present == 0) return Optional.empty();
        if (present != 3) throw new IllegalArgumentException("give all of x, y, z or none");
        return Optional.of(new BlockPos(
                args.get("x").getAsInt(),
                args.get("y").getAsInt(),
                args.get("z").getAsInt()));
    }

    private static ToolMeta metadata(String name) {
        return new ToolMeta(name, ToolGroup.FARMING, false, "", List.of(), Cost.HIGH);
    }
}
