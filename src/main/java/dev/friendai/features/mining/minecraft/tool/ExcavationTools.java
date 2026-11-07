package dev.friendai.features.mining.minecraft.tool;

import com.google.gson.JsonObject;
import dev.friendai.agent.tools.Cost;
import dev.friendai.agent.tools.ToolGroup;
import dev.friendai.agent.tools.ToolMeta;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.execution.tool.SchemaBuilder;
import dev.friendai.features.mining.excavation.ExcavationDirection;
import dev.friendai.features.mining.excavation.ExcavationGeometry;
import dev.friendai.features.mining.excavation.ExcavationShape;
import dev.friendai.features.mining.excavation.ExcavationSpec;
import dev.friendai.features.mining.minecraft.task.ExcavateTask;
import dev.friendai.features.mining.minecraft.task.ExcavationCheckpoints;
import dev.friendai.features.places.minecraft.PlaceService;
import dev.friendai.server.execution.tools.FeatureToolContributor;
import dev.friendai.server.execution.tools.FeatureToolRegistry;
import dev.friendai.server.execution.tools.TaskToolDefinition;
import dev.friendai.task.Task;
import dev.friendai.task.inventory.ItemMatcher;
import dev.friendai.world.targeting.NearResolver;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;

/** Safe solid-region excavation: a box, tunnel, stair, shaft or strip mine through the shared mining session. */
public final class ExcavationTools implements FeatureToolContributor {
    private static final Set<String> TOP_LEVEL = Set.of(
            "shape",
            "box",
            "run",
            "start",
            "count",
            "until_item",
            "until_count",
            "collect",
            "containers",
            "allow_liquids",
            "require_tool",
            "reserve_durability");
    private static final Set<String> BOX_FIELDS = Set.of("x1", "y1", "z1", "x2", "y2", "z2");
    private static final Set<String> RUN_FIELDS =
            Set.of("direction", "length", "width", "height", "branches", "spacing", "branch_length");
    private static final Set<String> START_FIELDS = Set.of("near", "x", "y", "z");

    private final BooleanSupplier collectDrops;
    private final PlaceService places;

    public ExcavationTools(BooleanSupplier collectDrops, PlaceService places) {
        this.collectDrops = Objects.requireNonNull(collectDrops);
        this.places = Objects.requireNonNull(places);
    }

    @Override
    public void register(FeatureToolRegistry registry) {
        registry.registerTask(new TaskToolDefinition(
                        "excavate",
                        "Mine a bounded solid region and collect the drops: a box between two corners, a tunnel, a "
                                + "descending stair, a shaft or a strip mine. One call covers the whole region; give "
                                + "count only to stop early, or until_item/until_count to stop when that many items "
                                + "were actually taken. Protected places, containers and lava are never broken by "
                                + "default. For clearing a plot before building use clear_area instead.",
                        schema(),
                        null,
                        this::create,
                        true,
                        AccessLevel.TRUSTED,
                        TaskToolDefinition.QueueTraits.DEFAULT,
                        true,
                        true,
                        metadata())
                .withCheckpoint("excavate", ExcavationCheckpoints::excavate));
    }

    private static JsonObject schema() {
        JsonObject box = SchemaBuilder.object()
                .integer("x1", "Box first corner X", -30000000, 30000000)
                .integer("y1", "Box first corner Y", -64, 320)
                .integer("z1", "Box first corner Z", -30000000, 30000000)
                .integer("x2", "Box opposite corner X", -30000000, 30000000)
                .integer("y2", "Box opposite corner Y", -64, 320)
                .integer("z2", "Box opposite corner Z", -30000000, 30000000)
                .required("x1", "y1", "z1", "x2", "y2", "z2")
                .build();
        JsonObject run = SchemaBuilder.object()
                .enumeration(
                        "direction",
                        "Run direction; stair descends one block per step; shaft uses down or up",
                        "north",
                        "south",
                        "east",
                        "west",
                        "down",
                        "up")
                .integer("length", "How many cells the run advances", 1, 128)
                .integer("width", "Cross-section width, 1-5", 1, 5)
                .integer("height", "Cross-section height, 1-5; a stair is always at least 3 so it can be walked", 1, 5)
                .integer("branches", "Strip-mine side branches", 0, 16)
                .integer("spacing", "Cells between strip branches", 2, 8)
                .integer("branch_length", "How far each strip branch runs", 1, 128)
                .build();
        JsonObject start = SchemaBuilder.object()
                .string("near", "Start around yourself (default), the speaker (me), or place:<name>")
                .integer("x", "Start X instead of near", -30000000, 30000000)
                .integer("y", "Start Y instead of near", -64, 320)
                .integer("z", "Start Z instead of near", -30000000, 30000000)
                .build();
        JsonObject schema = SchemaBuilder.object()
                .enumeration("shape", "box, tunnel, stair, shaft or strip", "box", "tunnel", "stair", "shaft", "strip")
                .integer("count", "Stop after this many broken blocks", 1, 4096)
                .string("until_item", "Stop once this many of this item were taken, e.g. minecraft:raw_iron")
                .integer("until_count", "How many of until_item to take before stopping", 1, 4096)
                .bool("collect", "Collect drops; default follows the server setting")
                .bool("containers", "Allow breaking containers (default false)")
                .bool("allow_liquids", "Allow fluid cells, never lava (default false)")
                .bool("require_tool", "Skip a cell that needs a tool the companion lacks (default true)")
                .integer("reserve_durability", "Stop before the tool reaches this percent", 0, 95)
                .required("shape")
                .build();
        JsonObject properties = schema.getAsJsonObject("properties");
        properties.add("box", box);
        properties.add("run", run);
        properties.add("start", start);
        return schema;
    }

    private Task create(CompanionEntity c, Caller caller, JsonObject a) {
        rejectUnknown(a, TOP_LEVEL, "argument");
        ExcavationShape shape = ExcavationShape.parse(a.get("shape").getAsString())
                .orElseThrow(() -> new IllegalArgumentException("shape must be box, tunnel, stair, shaft or strip"));
        JsonObject box = objectArg(a, "box", BOX_FIELDS);
        JsonObject run = objectArg(a, "run", RUN_FIELDS);
        JsonObject start = objectArg(a, "start", START_FIELDS);
        boolean collect = boolArg(a, "collect", collectDrops.getAsBoolean());
        String untilItem = stringOrNull(a, "until_item");
        boolean hasUntilCount = a.has("until_count") && !a.get("until_count").isJsonNull();
        if ((untilItem == null) == hasUntilCount) {
            throw new IllegalArgumentException("give both until_item and until_count, or neither");
        }
        if (untilItem != null && !collect) {
            throw new IllegalArgumentException("until_item needs collect:true, because it counts items actually taken");
        }
        if (untilItem != null) {
            ItemMatcher.parse(untilItem).orElseThrow(() -> new IllegalArgumentException("unknown item: " + untilItem));
        }
        int untilCount = hasUntilCount ? a.get("until_count").getAsInt() : 0;
        ExcavationGeometry geometry;
        if (shape == ExcavationShape.BOX) {
            if (run != null || start != null) {
                throw new IllegalArgumentException("run and start do not apply to a box; give its two corners");
            }
            if (box == null) {
                throw new IllegalArgumentException("a box needs the box object with x1,y1,z1 and x2,y2,z2");
            }
            geometry = box(box);
        } else {
            if (box != null) {
                throw new IllegalArgumentException("a box object does not apply to "
                        + shape.name().toLowerCase() + "; give run and start instead");
            }
            if (run == null) {
                throw new IllegalArgumentException("give run with direction and length for a "
                        + shape.name().toLowerCase());
            }
            geometry = corridor(shape, c, caller, run, start == null ? new JsonObject() : start);
        }
        int count =
                a.has("count") && !a.get("count").isJsonNull() ? a.get("count").getAsInt() : geometry.volume();
        ExcavationSpec spec = ExcavationSpec.of(
                        geometry,
                        count,
                        untilItem,
                        untilCount,
                        collect,
                        boolArg(a, "containers", false),
                        boolArg(a, "allow_liquids", false),
                        boolArg(a, "require_tool", true),
                        intArg(a, "reserve_durability", 0))
                .orElseThrow(() -> new IllegalArgumentException(
                        "invalid excavation: check the shape fields, count, until pair and reserve_durability"));
        return new ExcavateTask(spec);
    }

    private static ExcavationGeometry box(JsonObject a) {
        return ExcavationGeometry.box(
                        requiredInt(a, "x1"),
                        requiredInt(a, "y1"),
                        requiredInt(a, "z1"),
                        requiredInt(a, "x2"),
                        requiredInt(a, "y2"),
                        requiredInt(a, "z2"))
                .orElseThrow(() -> new IllegalArgumentException(
                        "the box is empty or larger than " + ExcavationGeometry.MAX_VOLUME + " cells"));
    }

    private ExcavationGeometry corridor(
            ExcavationShape shape, CompanionEntity c, Caller caller, JsonObject run, JsonObject start) {
        ExcavationDirection direction =
                run.has("direction") && !run.get("direction").isJsonNull()
                        ? ExcavationDirection.parse(run.get("direction").getAsString())
                                .orElseThrow(() -> new IllegalArgumentException("unknown direction"))
                        : shape == ExcavationShape.SHAFT ? ExcavationDirection.DOWN : null;
        if (direction == null) {
            throw new IllegalArgumentException(
                    "give direction for a " + shape.name().toLowerCase());
        }
        int length = requiredInt(run, "length");
        int width = intArg(run, "width", 1);
        int height = intArg(run, "height", 2);
        BlockPos origin = NearResolver.resolve(places, c, caller, start).pos();
        return switch (shape) {
            case TUNNEL ->
                ExcavationGeometry.tunnel(direction, origin.getX(), origin.getY(), origin.getZ(), length, width, height)
                        .orElseThrow(ExcavationTools::invalidRun);
            case STAIR ->
                ExcavationGeometry.stair(direction, origin.getX(), origin.getY(), origin.getZ(), length, width, height)
                        .orElseThrow(ExcavationTools::invalidRun);
            case SHAFT ->
                ExcavationGeometry.shaft(direction, origin.getX(), origin.getY(), origin.getZ(), length, width, height)
                        .orElseThrow(ExcavationTools::invalidRun);
            case STRIP ->
                ExcavationGeometry.strip(
                                direction,
                                origin.getX(),
                                origin.getY(),
                                origin.getZ(),
                                length,
                                width,
                                height,
                                intArg(run, "branches", 0),
                                intArg(run, "spacing", 4),
                                intArg(run, "branch_length", length))
                        .orElseThrow(ExcavationTools::invalidRun);
            default -> throw new IllegalArgumentException("box is handled separately");
        };
    }

    private static IllegalArgumentException invalidRun() {
        return new IllegalArgumentException(
                "invalid run: length is 1-128, width/height 1-5, and the region must stay within "
                        + ExcavationGeometry.MAX_VOLUME + " cells");
    }

    private static JsonObject objectArg(JsonObject args, String name, Set<String> allowed) {
        if (!args.has(name) || args.get(name).isJsonNull()) {
            return null;
        }
        if (!args.get(name).isJsonObject()) {
            throw new IllegalArgumentException(name + " must be an object");
        }
        JsonObject value = args.getAsJsonObject(name);
        rejectUnknown(value, allowed, name);
        return value;
    }

    private static void rejectUnknown(JsonObject args, Set<String> allowed, String where) {
        for (String key : args.keySet()) {
            if (!allowed.contains(key)) {
                throw new IllegalArgumentException(
                        "unknown " + where + " '" + key + "'; accepted: " + String.join(", ", allowed));
            }
        }
    }

    private static int requiredInt(JsonObject args, String field) {
        if (!args.has(field) || args.get(field).isJsonNull()) {
            throw new IllegalArgumentException("give " + field);
        }
        return args.get(field).getAsInt();
    }

    private static boolean boolArg(JsonObject args, String name, boolean fallback) {
        return args.has(name) && !args.get(name).isJsonNull() ? args.get(name).getAsBoolean() : fallback;
    }

    private static int intArg(JsonObject args, String name, int fallback) {
        return args.has(name) && !args.get(name).isJsonNull() ? args.get(name).getAsInt() : fallback;
    }

    private static String stringOrNull(JsonObject args, String name) {
        return args.has(name) && !args.get(name).isJsonNull() ? args.get(name).getAsString() : null;
    }

    private static ToolMeta metadata() {
        String hint = "- Use excavate for a shape: box (two corners in box), tunnel (run direction/length), stair "
                + "(descends one block per step, always at least three blocks high), shaft (down/up) or strip "
                + "(branches). "
                + "One call covers the region; "
                + "count stops early, until_item/until_count stop on taken items.\n"
                + "- Protected places, containers and lava are never broken by default; containers:true is the only "
                + "opt-in. Use clear_area to clear a plot for building, not to mine its solids.";
        return new ToolMeta("excavate", ToolGroup.GATHERING, false, hint, List.of(), Cost.HIGH);
    }
}
