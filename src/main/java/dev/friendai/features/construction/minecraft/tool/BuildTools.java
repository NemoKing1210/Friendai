package dev.friendai.features.construction.minecraft.tool;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.friendai.agent.tools.ToolGroup;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.tool.SchemaBuilder;
import dev.friendai.execution.tool.ToolResult;
import dev.friendai.features.construction.blueprint.BlockSpec;
import dev.friendai.features.construction.blueprint.Blueprint;
import dev.friendai.features.construction.blueprint.BlueprintParser;
import dev.friendai.features.construction.blueprint.ParseResult;
import dev.friendai.features.construction.blueprint.PartKind;
import dev.friendai.features.construction.blueprint.StructureParts;
import dev.friendai.features.construction.minecraft.build.BlueprintResolver;
import dev.friendai.features.construction.minecraft.build.BlueprintStore;
import dev.friendai.features.construction.minecraft.build.Materials;
import dev.friendai.features.construction.minecraft.build.Placement;
import dev.friendai.features.construction.minecraft.build.PreviewIntent;
import dev.friendai.features.construction.minecraft.build.PreviewOutline;
import dev.friendai.features.construction.minecraft.build.Site;
import dev.friendai.features.construction.minecraft.task.BuildTask;
import dev.friendai.features.placements.minecraft.PlacedService;
import dev.friendai.server.execution.tools.FeatureToolRegistry;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Rotation;

/** Building: blueprint help, previews, builds and the blueprint store. */
final class BuildTools {
    private BuildTools() {}
    /** At most this many structure template ids are listed, to keep the result small. */
    private static final int MAX_TEMPLATES = 30;

    static final String HELP = """
            A structure is a blueprint (JSON you write), a generator (JSON for a standard shape), a saved name or a
            structure template.
            Give exactly one of "blueprint", "generator", "saved", "structure" (a template id from list_blueprints).
            Blueprint format (JSON):
            {"name":"hut",
             "palette":{"C":"minecraft:cobblestone","P":"minecraft:oak_planks","_":"minecraft:air"},
             "ops":[
               {"op":"box","from":[0,0,0],"to":[4,0,4],"block":"C"},
               {"op":"walls","from":[0,1,0],"to":[4,2,4],"block":"P","corners":"C"},
               {"op":"roof","from":[0,3,0],"to":[4,3,4],"style":"flat","block":"minecraft:oak_slab"}]}
            - palette: one-character keys (not "."); values are block states like "minecraft:oak_log[axis=x]".
            - Coordinates are [x,y,z]: x west-east, y bottom-up, z north-south; the origin is the minimum corner.
            - "block" takes a palette key or a block id. "." in layers means "leave unchanged"; "_" is the usual air key.
            - ops, applied in order:
              box (from, to, block, optional hollow), walls (four sides, optional corners), floor (box of height 1),
              line (axis-aligned from/to), roof (style gable/pyramid/flat; block is stairs, slab, planks or a full
              block with a stairs variant; axis for gable),
              door (at, facing, block), window (from, to on a wall, block, default glass pane).
            - layers: optional; layer i is y = i, each layer is rows (z) of equal-length strings (x); they apply after ops.
            - Limits: at most 32 blocks on each axis and 8192 blocks. Unlisted positions are left unchanged.
            Generators (pass the JSON as the "generator" argument):
            - house: width 5..15, depth 5..15, floors 1..3, walls, floor, frame (optional log id), roof (stairs, slab,
              planks or a full block with a stairs variant; optional, derived from walls),
              roof_style gable|pyramid|flat, windows (glass pane id, default glass pane, or "none" for solid walls),
              door_side north|east|south|west, door (door id, default from the walls, or "none" for an open doorway;
              without a door or glass carried, pass "none" so the house needs only its wall, floor and roof blocks),
              torches true|false (default false; set it only when torches are carried or the owner asked for light,
              because a missing torch makes the build stop or, with gather, go mining for coal first).
            - tower: size 3..9, height 4..24, walls, ladder true|false; crenellations on top.
            - wall: length 1..32, height 1..8, block, axis x|z.
            - fence: width 3..32, depth 3..32, block (fence id), gate (gate id), gate_side.
            - platform: width 1..32, depth 1..32, block.
            - bridge: length 3..32, width 1..5, block, railing (fence id, optional).
            preview_structure shows the size, materials and site problems, and takes the same clear_site, gather and
            parts options as build_structure: it describes exactly the build those options would make.
            build_structure takes clear_site (default true), gather (default false, obtain missing materials with
            obtain_item instead of failing) and parts (floor, walls, roof, entrance, window; omit it to build
            everything). Calling it again for a half-built structure resumes that build; an option given then
            applies to the rest of the work, an omitted one keeps the saved value, and a facing the build already
            used cannot be turned.
            build_structure with use_preview=true builds exactly the last preview's site and options.
            Errors name the place and the reason, for example:
            palette: key "CC" must be one character, ops[1]: 'to' is below 'from', unknown block minecraft:oak_plank.
            """;

    static void register(
            FeatureToolRegistry registry, BuildTask.ObtainFactory obtainFactory, PlacedService placedService) {
        ConstructionToolSupport.registerTool(
                registry,
                ToolGroup.BLUEPRINT,
                "blueprint_help",
                "Explain the blueprint JSON format and the structure generators; read it before writing a blueprint.",
                SchemaBuilder.object().build(),
                (c, a) -> ToolResult.ok(HELP));
        ConstructionToolSupport.registerTool(
                registry,
                ToolGroup.BLUEPRINT,
                "preview_structure",
                "Check a structure before building it: size, materials, site problems, and an outline for the owner."
                        + " Takes the same clear_site, gather and parts options as build_structure.",
                previewSchema(),
                (companion, args) -> preview(placedService, companion, args));
        ConstructionToolSupport.registerTask(
                registry,
                ToolGroup.BLUEPRINT,
                "build_structure",
                "Build a structure from a blueprint, a generator or a saved blueprint, taking missing materials from nearby storage.",
                buildSchema(),
                (c, a) -> {
                    ServerLevel level = (ServerLevel) c.level();
                    if (usePreview(a)) {
                        return fromPreview(c, level, a, obtainFactory);
                    }
                    ParseResult parsed = BlueprintResolver.resolve(a, level, author(c));
                    if (parsed.blueprint().isEmpty()) {
                        throw new IllegalArgumentException(String.join("; ", parsed.errors()));
                    }
                    Blueprint blueprint = parsed.blueprint().get();
                    BlockPos requested = origin(a);
                    String dimension = level.dimension().identifier().toString();
                    Optional<BlueprintStore.InProgress> resumable = BlueprintStore.forLevel(level)
                            .findResumable(blueprint, c.ownerUuid(), dimension, requested);
                    if (resumable.isPresent()) {
                        return resumed(
                                resumable.get(),
                                rotationOption(a),
                                clearSiteOption(a),
                                gatherOption(a),
                                partsOption(a),
                                obtainFactory);
                    }
                    Placement.PlacedBlueprint placed = Placement.of(blueprint, requested, facing(a), c);
                    return new BuildTask(
                            blueprint,
                            placed.origin(),
                            placed.rotation(),
                            clearSite(a),
                            gather(a),
                            obtainFactory,
                            parts(a));
                },
                true,
                "build_structure",
                (state, context) -> dev.friendai.features.construction.minecraft.task.BuildCheckpoints.build(
                        state, context, obtainFactory));
        ConstructionToolSupport.registerTool(
                registry,
                ToolGroup.BLUEPRINT,
                "save_blueprint",
                "Save a blueprint under a name so it can be built again later.",
                SchemaBuilder.object()
                        .string("name", "Name of the blueprint: a-z, 0-9, _ and -, at most 32 characters")
                        .string("blueprint", "Blueprint JSON; defaults to the last previewed structure")
                        .bool("overwrite", "Replace an existing blueprint with the same name")
                        .required("name")
                        .build(),
                BuildTools::save);
        ConstructionToolSupport.registerTool(
                registry,
                ToolGroup.BLUEPRINT,
                "list_blueprints",
                "List saved blueprints with their sizes and authors, and structure templates matching the query.",
                SchemaBuilder.object()
                        .string("query", "Only blueprint names and template ids containing this text")
                        .build(),
                BuildTools::list);
    }

    private static SchemaBuilder sourceSchema() {
        return SchemaBuilder.object()
                .string("blueprint", "Blueprint JSON; blueprint_help explains the format")
                .string("generator", "Generator JSON, for example {\"type\":\"house\",\"width\":7,\"depth\":7}")
                .string("saved", "Name of a saved blueprint")
                .string(
                        "structure",
                        "Structure template id, for example minecraft:village/plains/houses/plains_small_house_1")
                .integer("x", "Origin X; give all of x, y, z or none for 3 blocks in front of the owner")
                .integer("y", "Origin Y")
                .integer("z", "Origin Z")
                .enumeration(
                        "facing",
                        "The direction the structure's front faces; on a resumed build the facing already built"
                                + " with cannot be turned",
                        "north",
                        "south",
                        "east",
                        "west");
    }

    private static JsonObject buildSchema() {
        return optionSchema()
                .bool(
                        "use_preview",
                        "Build exactly the structure and site preview_structure showed, ignoring x/y/z,"
                                + " facing and the other options (give use_preview alone)")
                .build();
    }

    /** The options a preview and a build share: the same values describe and execute one intent. */
    private static SchemaBuilder optionSchema() {
        return sourceSchema()
                .bool(
                        "clear_site",
                        "Break natural ground on the site (default true); on a resumed build it applies from now on")
                .bool(
                        "gather",
                        "Obtain missing materials with obtain_item instead of failing (default false); on a resumed"
                                + " build it applies to the rest")
                .array(
                        "parts",
                        "Build only these parts: floor, walls, roof, entrance, window; omit for everything; on a"
                                + " resumed build the selection replaces the saved one",
                        partSchema(),
                        0,
                        5);
    }

    private static JsonObject previewSchema() {
        return optionSchema().build();
    }

    /** One part name; the tool validates it against {@link PartKind}. */
    private static JsonObject partSchema() {
        return com.google.gson.JsonParser.parseString("{\"type\":\"string\"}").getAsJsonObject();
    }

    private static ToolResult preview(PlacedService placedService, CompanionEntity companion, JsonObject args) {
        ServerLevel level = (ServerLevel) companion.level();
        ParseResult parsed = BlueprintResolver.resolve(args, level, author(companion));
        if (parsed.blueprint().isEmpty()) {
            return ToolResult.error(String.join("; ", parsed.errors()));
        }
        Blueprint blueprint = parsed.blueprint().get();
        Placement.PlacedBlueprint placed;
        Placement.PlacedBlueprint scoped;
        Site.SiteReport site;
        List<Placement.PlacedSpec> remaining;
        Map<Item, Integer> needed;
        StructureParts structure;
        Set<PartKind> parts;
        boolean clearSite;
        boolean gather;
        try {
            // The preview describes the same plan the build executes: same options, same footprint, same site pass.
            parts = parts(args);
            clearSite = clearSite(args);
            gather = gather(args);
            structure = StructureParts.analyse(blueprint);
            Blueprint buildPlan = parts.isEmpty()
                    ? blueprint
                    : new Blueprint(
                            blueprint.name(),
                            blueprint.width(),
                            blueprint.height(),
                            blueprint.depth(),
                            structure.cells(parts));
            if (!parts.isEmpty() && buildPlan.blocks().isEmpty()) {
                return ToolResult.error(
                        "the blueprint has no " + StructureParts.names(parts) + "; omit parts to build all of it");
            }
            placed = Placement.of(blueprint, origin(args), facing(args), companion);
            scoped = Placement.at(buildPlan, placed.origin(), placed.rotation(), new Placement.StateCache());
            Set<BlockPos> owned = Site.ownedByCompanion(companion, scoped, placedService);
            Site.Staged staged = new Site.Staged(level, scoped, companion, clearSite, owned::contains);
            staged.step(Integer.MAX_VALUE);
            site = staged.report();
            remaining = staged.remaining();
            needed = Materials.needed(remaining, site.foundation());
            companion.setLastPreview(blueprint);
            companion.rememberPreview(new PreviewIntent(
                    blueprint,
                    placed.origin(),
                    placed.rotation(),
                    clearSite,
                    gather,
                    parts,
                    level.dimension().identifier().toString()));
            companion
                    .owner()
                    .ifPresent(owner -> companion.showOutline(new PreviewOutline(owner.getUUID(), scoped, buildPlan)));
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        }
        return ToolResult.ok(previewText(
                blueprint,
                structure,
                placed,
                site,
                remaining,
                needed,
                Materials.have(companion, level),
                Materials.carried(companion),
                parts,
                clearSite,
                gather,
                parsed.notes()));
    }

    private static String previewText(
            Blueprint blueprint,
            StructureParts structure,
            Placement.PlacedBlueprint placed,
            Site.SiteReport site,
            List<Placement.PlacedSpec> remaining,
            Map<Item, Integer> needed,
            Map<Item, Integer> have,
            Map<Item, Integer> carried,
            Set<PartKind> parts,
            boolean clearSite,
            boolean gather,
            List<String> notes) {
        long planned = blocksToPlace(placed.specs());
        long blocks = blocksToPlace(remaining);
        long done = planned - blocks;
        return blueprint.name() + " " + blueprint.width() + "×" + blueprint.height() + "×" + blueprint.depth()
                + " at " + coords(placed.origin())
                + " facing " + Placement.facingFor(placed.rotation()).getSerializedName()
                + ": " + blocks + " blocks to place"
                + (done > 0 ? ", " + done + " already placed" : "")
                + ", " + site.toClear().size() + " to clear, "
                + site.foundation().size() + " foundation; materials: "
                + materialsText(needed, have, carried)
                + "; site: " + (site.problems().isEmpty() ? "clear" : String.join("; ", site.problems()))
                + "; parts: " + structure.describe(parts)
                + (clearSite ? "" : "; clear site off")
                + (gather ? "; gather missing materials" : "")
                + (notes.isEmpty() ? "" : "; " + String.join("; ", notes))
                + "; build it as shown with build_structure use_preview=true";
    }

    private static long blocksToPlace(List<Placement.PlacedSpec> specs) {
        return specs.stream().filter(spec -> !spec.wanted().isAir()).count();
    }

    private static boolean usePreview(JsonObject args) {
        return present(args, "use_preview") && args.get("use_preview").getAsBoolean();
    }

    /**
     * Builds exactly what {@code preview_structure} showed: its site, rotation, parts, clearing and gathering rules.
     * Any other argument would make the build differ from what the owner saw, so it is refused; the model previews
     * again instead.
     */
    private static BuildTask fromPreview(
            CompanionEntity companion, ServerLevel level, JsonObject args, BuildTask.ObtainFactory obtainFactory) {
        for (String key : args.keySet()) {
            if (!key.equals("use_preview")) {
                throw new IllegalArgumentException("use_preview builds exactly the last preview; call preview_structure"
                        + " with the new options first");
            }
        }
        PreviewIntent intent = companion
                .previewIntent()
                .orElseThrow(() -> new IllegalArgumentException("no preview to build: call preview_structure first"));
        String dimension = level.dimension().identifier().toString();
        if (!intent.dimension().equals(dimension)) {
            throw new IllegalArgumentException(
                    "the preview was in " + intent.dimension() + "; call preview_structure here to see that site");
        }
        Optional<BlueprintStore.InProgress> resumable = BlueprintStore.forLevel(level)
                .findResumable(intent.blueprint(), companion.ownerUuid(), dimension, intent.origin());
        if (resumable.isPresent()) {
            // The preview authorized this exact intent, so its options replace whatever the record held.
            return resumed(
                    resumable.get(),
                    intent.rotation(),
                    intent.clearSite(),
                    intent.gather(),
                    intent.parts(),
                    obtainFactory);
        }
        return new BuildTask(
                intent.blueprint(),
                intent.origin(),
                intent.rotation(),
                intent.clearSite(),
                intent.gather(),
                obtainFactory,
                intent.parts());
    }

    /**
     * Resumes a record with the options this call names: a value given here replaces the saved one for the rest of
     * the work, an omitted one keeps it, so repeating the call continues exactly as before. A new facing turns the
     * whole footprint, so it is only accepted while the record shows no progress.
     */
    private static BuildTask resumed(
            BlueprintStore.InProgress record,
            Rotation rotation,
            Boolean clearSite,
            Boolean gather,
            Set<PartKind> parts,
            BuildTask.ObtainFactory obtainFactory) {
        Rotation effective = rotation == null ? record.rotation() : rotation;
        if (effective != record.rotation() && started(record)) {
            throw new IllegalArgumentException("the build at " + coords(record.origin()) + " already has blocks facing "
                    + Placement.facingFor(record.rotation()).getSerializedName()
                    + "; finish it or start a new build elsewhere instead of turning it");
        }
        return BuildTask.resume(
                record.blueprint(),
                record.origin(),
                effective,
                clearSite == null ? record.clearSite() : clearSite,
                gather == null ? record.gather() : gather,
                record.scaffold(),
                record.id(),
                parts == null ? record.parts() : parts,
                obtainFactory);
    }

    /**
     * Whether the record's build changed the world: it placed a scaffold, or fewer cells are left to place than the
     * plan needs. A record whose remaining cells are unknown (an older file) counts as started.
     */
    private static boolean started(BlueprintStore.InProgress record) {
        if (!record.scaffold().isEmpty()) {
            return true;
        }
        Placement.StateCache cache = new Placement.StateCache();
        int planned = 0;
        for (BlockSpec spec : record.blueprint().blocks()) {
            try {
                if (!cache.parse(spec.state()).isAir()) {
                    planned++;
                }
            } catch (IllegalArgumentException e) {
                return true;
            }
        }
        return record.remaining().size() < planned;
    }

    private static String materialsText(
            Map<Item, Integer> needed, Map<Item, Integer> have, Map<Item, Integer> carried) {
        if (needed.isEmpty()) {
            return "none";
        }
        StringBuilder text = new StringBuilder();
        for (Map.Entry<Item, Integer> entry : needed.entrySet()) {
            Item item = entry.getKey();
            int atHand = carried.getOrDefault(item, 0);
            int total = have.getOrDefault(item, 0);
            int stored = Math.max(0, total - atHand);
            int missing = Math.max(0, entry.getValue() - total);
            if (text.length() > 0) {
                text.append(", ");
            }
            text.append(entry.getValue())
                    .append(" × ")
                    .append(BuiltInRegistries.ITEM.getKey(item))
                    .append(" (have ")
                    .append(atHand);
            if (stored > 0) {
                text.append(" + ").append(stored).append(" in chests");
            }
            text.append(", missing ").append(missing).append(')');
        }
        return text.toString();
    }

    private static ToolResult save(CompanionEntity companion, JsonObject args) {
        String name = args.get("name").getAsString();
        Blueprint blueprint;
        if (present(args, "blueprint")) {
            ParseResult parsed =
                    BlueprintParser.parse(args.get("blueprint").getAsString(), BlueprintResolver.knownState());
            if (parsed.blueprint().isEmpty()) {
                return ToolResult.error(String.join("; ", parsed.errors()));
            }
            blueprint = parsed.blueprint().get();
        } else {
            blueprint = companion.lastPreview().orElse(null);
            if (blueprint == null) {
                return ToolResult.error("no blueprint to save: give a blueprint or call preview_structure first");
            }
        }
        boolean overwrite = present(args, "overwrite") && args.get("overwrite").getAsBoolean();
        ServerLevel level = (ServerLevel) companion.level();
        try {
            BlueprintStore.forLevel(level).save(name, blueprint, author(companion), overwrite);
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        }
        return ToolResult.ok(
                "saved blueprint " + name + " (" + blueprint.blocks().size() + " blocks)");
    }

    private static ToolResult list(CompanionEntity companion, JsonObject args) {
        String query = present(args, "query") ? args.get("query").getAsString().toLowerCase(Locale.ROOT) : "";
        ServerLevel level = (ServerLevel) companion.level();
        List<BlueprintStore.Entry> entries = BlueprintStore.forLevel(level).list().stream()
                .filter(entry -> entry.name().toLowerCase(Locale.ROOT).contains(query))
                .toList();
        List<String> templates = level.getServer()
                .getStructureTemplateManager()
                .listTemplates()
                .map(Identifier::toString)
                .filter(id -> id.toLowerCase(Locale.ROOT).contains(query))
                .sorted()
                .limit(MAX_TEMPLATES)
                .toList();
        StringBuilder text = new StringBuilder();
        if (!entries.isEmpty()) {
            text.append(entries.size() == 1 ? "1 blueprint: " : entries.size() + " blueprints: ");
            for (int i = 0; i < entries.size(); i++) {
                BlueprintStore.Entry entry = entries.get(i);
                if (i > 0) {
                    text.append(", ");
                }
                text.append(entry.name())
                        .append(' ')
                        .append(entry.width())
                        .append('×')
                        .append(entry.height())
                        .append('×')
                        .append(entry.depth())
                        .append(" by ")
                        .append(authorName(level, entry.author()));
            }
        }
        if (!templates.isEmpty()) {
            if (text.length() > 0) {
                text.append('\n');
            }
            text.append(templates.size() == 1 ? "1 structure template: " : templates.size() + " structure templates: ");
            text.append(String.join(", ", templates));
        }
        if (text.length() == 0) {
            return ToolResult.ok(
                    query.isEmpty()
                            ? "no saved blueprints or structure templates"
                            : "no saved blueprints or structure templates matching " + query);
        }
        return ToolResult.ok(text.toString());
    }

    private static String authorName(ServerLevel level, Optional<UUID> author) {
        return author.map(uuid -> level.getServer().getPlayerList().getPlayer(uuid))
                .map(player -> player.getName().getString())
                .orElse("unknown");
    }

    private static UUID author(CompanionEntity companion) {
        return companion.owner().map(owner -> owner.getUUID()).orElse(null);
    }

    static BlockPos origin(JsonObject args) {
        return optionalPos(args).orElse(null);
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

    static Direction facing(JsonObject args) {
        if (!present(args, "facing")) {
            return null;
        }
        return switch (args.get("facing").getAsString().toLowerCase(Locale.ROOT)) {
            case "north" -> Direction.NORTH;
            case "south" -> Direction.SOUTH;
            case "east" -> Direction.EAST;
            case "west" -> Direction.WEST;
            default -> throw new IllegalArgumentException("facing must be north, south, east or west");
        };
    }

    private static boolean clearSite(JsonObject args) {
        return !present(args, "clear_site") || args.get("clear_site").getAsBoolean();
    }

    /** Whether clearing was requested, or null when the call left it out. */
    private static Boolean clearSiteOption(JsonObject args) {
        return present(args, "clear_site") ? args.get("clear_site").getAsBoolean() : null;
    }

    /** Whether obtaining was requested, or null when the call left it out. */
    private static Boolean gatherOption(JsonObject args) {
        return present(args, "gather") ? args.get("gather").getAsBoolean() : null;
    }

    /** The selected parts, or null when the call left them out; an empty array selects every part. */
    private static Set<PartKind> partsOption(JsonObject args) {
        return present(args, "parts") ? parts(args) : null;
    }

    /** The facing as a rotation, or null when the call left it out. */
    private static Rotation rotationOption(JsonObject args) {
        Direction direction = facing(args);
        return direction == null ? null : Placement.rotationFor(direction);
    }

    private static Set<PartKind> parts(JsonObject args) {
        if (!present(args, "parts")) {
            return Set.of();
        }
        if (!args.get("parts").isJsonArray()) {
            throw new IllegalArgumentException("parts must be an array of part names");
        }
        Set<PartKind> selected = new LinkedHashSet<>();
        for (JsonElement element : args.getAsJsonArray("parts")) {
            if (!element.isJsonPrimitive()) {
                throw new IllegalArgumentException("parts must contain names, not objects");
            }
            String name = element.getAsString();
            PartKind kind = PartKind.parse(name)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "unknown part '" + name + "'; use floor, walls, roof, entrance or window"));
            if (kind == PartKind.INTERIOR) {
                throw new IllegalArgumentException("the interior is empty space and is not built");
            }
            if (kind == PartKind.OTHER) {
                throw new IllegalArgumentException(
                        "'other' is not a part to build; use floor, walls, roof, entrance or window");
            }
            selected.add(kind);
        }
        return Set.copyOf(selected);
    }

    private static boolean gather(JsonObject args) {
        return present(args, "gather") && args.get("gather").getAsBoolean();
    }

    private static boolean present(JsonObject args, String name) {
        return args.has(name) && !args.get(name).isJsonNull();
    }

    private static String coords(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }
}
