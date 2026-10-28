package dev.friendai.tool.builtin;

import com.google.gson.JsonObject;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.execution.tool.SchemaBuilder;
import dev.friendai.execution.tool.ToolRegistry;
import dev.friendai.execution.tool.ToolResult;
import dev.friendai.features.navigation.pathing.Pos;
import dev.friendai.place.Place;
import dev.friendai.place.PlaceKind;
import dev.friendai.place.PlaceQuery;
import dev.friendai.place.PlaceService;
import dev.friendai.place.PlaceSource;
import dev.friendai.place.PlaceText;
import dev.friendai.place.PlaceValues;
import dev.friendai.task.Task;
import dev.friendai.task.builtin.GoToPlaceTask;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** The owner's book of named places: remember, find, walk to, rename and forget. */
final class PlaceTools {
    private static final int DEFAULT_RADIUS = 8;
    private static final int SUMMARY_MAX_CHARS = 1_000;
    /** A speaker further away than this cannot mean their own position as "here". */
    private static final double MAX_SPEAKER_DISTANCE = 64.0;

    private PlaceTools() {}

    static void register(ToolRegistry<CompanionEntity> registry, List<TaskToolDef> defs) {
        registry.register(ToolKit.callerTool(
                "remember_place",
                "Remember a place the owner cares about, so it can be found and walked to later. "
                        + "Use the name the owner used; here:true stores the speaker's position and false yours.",
                rememberSchema(),
                AccessLevel.TRUSTED,
                () -> true,
                PlaceTools::remember));

        registry.register(ToolKit.instantTool(
                "find_place",
                "List remembered places matching text, a kind or a dimension, nearest first in the current "
                        + "dimension. Use the name from the result with go_to_place; this only reports.",
                findSchema(),
                AccessLevel.TRUSTED,
                PlaceTools::find));

        ToolKit.add(
                registry,
                defs,
                new TaskToolDef(
                        "go_to_place",
                        "Walk to a remembered place by name or alias. Use find_place first when the name is unclear.",
                        SchemaBuilder.object()
                                .string("name", "Exact place name or alias")
                                .required("name")
                                .build(),
                        PlaceTools::goTo,
                        true,
                        AccessLevel.TRUSTED,
                        new TaskToolDef.QueueTraits(true, null)));

        registry.register(ToolKit.callerTool(
                "rename_place",
                "Give a remembered place a new name, keeping its id, aliases and visits.",
                SchemaBuilder.object()
                        .string("name", "Current place name or alias")
                        .string("new_name", "New name")
                        .required("name", "new_name")
                        .build(),
                AccessLevel.OWNER,
                () -> true,
                PlaceTools::rename));

        registry.register(ToolKit.callerTool(
                "forget_place",
                "Delete a remembered place by name or alias.",
                SchemaBuilder.object()
                        .string("name", "Place name or alias")
                        .required("name")
                        .build(),
                AccessLevel.OWNER,
                () -> true,
                PlaceTools::forget));
    }

    private static JsonObject rememberSchema() {
        return SchemaBuilder.object()
                .string("name", "Short name the owner used, at most 32 characters")
                .enumeration("kind", "What the place is for", kindNames())
                .string("label", "Optional short detail, such as the crop or the building style")
                .string("note", "Optional one-line remark")
                .string("aliases", "Optional other names, separated by commas")
                .integer("radius", "How many blocks around the anchor the place covers", 1, Place.RADIUS_MAX)
                .integer(
                        "below",
                        "How far below the anchor the place reaches; default 1 (its floor, not the " + "rock under it)",
                        0,
                        Place.RADIUS_MAX)
                .integer(
                        "above",
                        "How far above the anchor the place reaches; default 8, at most the radius",
                        0,
                        Place.RADIUS_MAX)
                .integer("x", "Anchor x; give all of x, y and z or none")
                .integer("y", "Anchor y; give all of x, y and z or none")
                .integer("z", "Anchor z; give all of x, y and z or none")
                .bool("here", "Store the speaker's position (default true), or your own when false")
                .required("name", "kind")
                .build();
    }

    private static JsonObject findSchema() {
        return SchemaBuilder.object()
                .string("text", "Name, alias or label fragment; blank lists every place")
                .enumeration("kind", "Only places of this kind", kindNames())
                .string("dimension", "Only places in this dimension id, such as minecraft:the_nether")
                .integer("limit", "Maximum number of places to return", PlaceQuery.LIMIT_MIN, PlaceQuery.LIMIT_MAX)
                .build();
    }

    private static String[] kindNames() {
        PlaceKind[] kinds = PlaceKind.values();
        String[] names = new String[kinds.length];
        for (int i = 0; i < kinds.length; i++) {
            names[i] = kinds[i].name().toLowerCase(Locale.ROOT);
        }
        return names;
    }

    private static ToolResult remember(CompanionEntity companion, Caller caller, JsonObject args) {
        try {
            PlaceValues values = values(companion, caller, args);
            Place place = PlaceService.remember(
                    companion, caller, values, companion.level().getGameTime());
            return ToolResult.ok("remembered " + place.name() + " as place #" + place.id() + " at "
                    + place.anchor().x() + " " + place.anchor().y() + " "
                    + place.anchor().z()
                    + "; find_place lists it and go_to_place walks there");
        } catch (IllegalArgumentException rejected) {
            return ToolResult.error(rejected.getMessage());
        }
    }

    private static PlaceValues values(CompanionEntity companion, Caller caller, JsonObject args) {
        BlockPos anchor = position(companion, caller, args);
        int radius = args.has("radius") && !args.get("radius").isJsonNull()
                ? args.get("radius").getAsInt()
                : DEFAULT_RADIUS;
        int below = args.has("below") && !args.get("below").isJsonNull()
                ? args.get("below").getAsInt()
                : Place.defaultBelow(radius);
        int above = args.has("above") && !args.get("above").isJsonNull()
                ? args.get("above").getAsInt()
                : Place.defaultAbove(radius);
        return new PlaceValues(
                args.get("name").getAsString(),
                PlaceKind.parse(args.get("kind").getAsString()),
                text(args, "label"),
                companion.level().dimension().identifier().toString(),
                new Pos(anchor.getX(), anchor.getY(), anchor.getZ()),
                radius,
                aliases(args),
                PlaceSource.MANUAL,
                caller.uuid(),
                text(args, "note"),
                below,
                above);
    }

    /**
     * Explicit coordinates win; otherwise {@code here} (default true) means the speaker's position and only
     * falls back to the companion's when the turn has no online player, as in a plan step.
     */
    private static BlockPos position(CompanionEntity companion, Caller caller, JsonObject args) {
        Optional<BlockPos> coordinates = ToolKit.optionalPos(args);
        if (coordinates.isPresent()) {
            return coordinates.get();
        }
        if (args.has("here")
                && !args.get("here").isJsonNull()
                && !args.get("here").getAsBoolean()) {
            return companion.blockPosition();
        }
        ServerPlayer speaker = speaker(companion, caller);
        if (speaker == null) {
            return companion.blockPosition();
        }
        if (speaker.level() != companion.level()) {
            throw new IllegalArgumentException("the player is in another dimension; give x, y and z or here:false");
        }
        if (speaker.distanceTo(companion) > MAX_SPEAKER_DISTANCE) {
            throw new IllegalArgumentException("the player is more than 64 blocks away; give x, y and z or here:false");
        }
        return speaker.blockPosition();
    }

    private static ServerPlayer speaker(CompanionEntity companion, Caller caller) {
        if (caller.uuid() == null || !(companion.level() instanceof ServerLevel level)) {
            return null;
        }
        return level.getServer().getPlayerList().getPlayer(caller.uuid());
    }

    private static ToolResult find(CompanionEntity companion, JsonObject args) {
        try {
            String text = text(args, "text");
            String dimension = text(args, "dimension");
            PlaceQuery.Request request = new PlaceQuery.Request(
                    text.isBlank() ? Optional.empty() : Optional.of(text),
                    args.has("kind") && !args.get("kind").isJsonNull()
                            ? Set.of(PlaceKind.parse(args.get("kind").getAsString()))
                            : Set.of(),
                    dimension.isBlank() ? Optional.empty() : Optional.of(dimension),
                    args.has("limit") && !args.get("limit").isJsonNull()
                            ? args.get("limit").getAsInt()
                            : PlaceQuery.LIMIT_DEFAULT,
                    true);
            List<PlaceQuery.Result> results = PlaceService.find(companion, request);
            String summary = PlaceText.summary(results, SUMMARY_MAX_CHARS);
            return ToolResult.ok(results.isEmpty() ? summary : summary + "\npass the name to go_to_place");
        } catch (IllegalArgumentException rejected) {
            return ToolResult.error(rejected.getMessage());
        }
    }

    private static Task goTo(CompanionEntity companion, JsonObject args) {
        return new GoToPlaceTask(
                PlaceService.requireReachable(companion, args.get("name").getAsString(), "go_to_place"));
    }

    private static ToolResult rename(CompanionEntity companion, Caller caller, JsonObject args) {
        String name = args.get("name").getAsString();
        try {
            Place renamed = PlaceService.rename(
                    companion,
                    caller,
                    name,
                    args.get("new_name").getAsString(),
                    companion.level().getGameTime());
            return ToolResult.ok("renamed " + name + " to " + renamed.name());
        } catch (IllegalArgumentException rejected) {
            return ToolResult.error(rejected.getMessage());
        }
    }

    private static ToolResult forget(CompanionEntity companion, Caller caller, JsonObject args) {
        String name = args.get("name").getAsString();
        try {
            boolean removed = PlaceService.forget(companion, caller, name);
            return ToolResult.ok(removed ? "forgot " + name : "there was no place called " + name);
        } catch (IllegalArgumentException rejected) {
            return ToolResult.error(rejected.getMessage());
        }
    }

    private static String text(JsonObject args, String key) {
        return args.has(key) && !args.get(key).isJsonNull() ? args.get(key).getAsString() : "";
    }

    private static Set<String> aliases(JsonObject args) {
        String raw = text(args, "aliases");
        if (raw.isBlank()) {
            return Set.of();
        }
        Set<String> aliases = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String alias = part.strip();
            if (!alias.isEmpty()) {
                aliases.add(alias);
            }
        }
        return aliases;
    }
}
