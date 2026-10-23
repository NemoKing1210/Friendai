package dev.friendai.tool.builtin;

import com.google.gson.JsonObject;
import dev.friendai.build.Blueprint;
import dev.friendai.build.BlueprintParser;
import dev.friendai.build.ParseResult;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.task.build.BlueprintResolver;
import dev.friendai.task.build.BlueprintStore;
import dev.friendai.task.build.Materials;
import dev.friendai.task.build.Placement;
import dev.friendai.task.build.PreviewOutline;
import dev.friendai.task.build.Site;
import dev.friendai.task.builtin.BuildTask;
import dev.friendai.tool.SchemaBuilder;
import dev.friendai.tool.ToolRegistry;
import dev.friendai.tool.ToolResult;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;

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
              line (axis-aligned from/to), roof (style gable/pyramid/flat; block is a stairs or slab id; axis for gable),
              door (at, facing, block), window (from, to on a wall, block, default glass pane).
            - layers: optional; layer i is y = i, each layer is rows (z) of equal-length strings (x); they apply after ops.
            - Limits: at most 32 blocks on each axis and 8192 blocks. Unlisted positions are left unchanged.

            Generators (pass the JSON as the "generator" argument):
            - house: width 5..15, depth 5..15, floors 1..3, walls, floor, frame (optional log id), roof (stairs id),
              roof_style gable|pyramid|flat, windows (glass pane id), door_side north|east|south|west, door (door id),
              torches true|false.
            - tower: size 3..9, height 4..24, walls, ladder true|false; crenellations on top.
            - wall: length 1..32, height 1..8, block, axis x|z.
            - fence: width 3..32, depth 3..32, block (fence id), gate (gate id), gate_side.
            - platform: width 1..32, depth 1..32, block.
            - bridge: length 3..32, width 1..5, block, railing (fence id, optional).

            preview_structure shows the size, materials and site problems. build_structure takes clear_site
            (default true) and gather (default false, obtain missing materials with obtain_item instead of failing).

            Errors name the place and the reason, for example:
            palette: key "CC" must be one character, ops[1]: 'to' is below 'from', unknown block minecraft:oak_plank.
            """;

    static void register(ToolRegistry<CompanionEntity> registry, List<TaskToolDef> defs) {
        registry.register(ToolKit.instantTool("blueprint_help",
                "Explain the blueprint JSON format and the structure generators; read it before writing a blueprint.",
                (c, a) -> ToolResult.ok(HELP)));
        registry.register(ToolKit.instantTool("preview_structure",
                "Check a structure before building it: size, materials, site problems, and an outline for the owner.",
                sourceSchema().build(), BuildTools::preview));
        ToolKit.add(registry, defs, new TaskToolDef("build_structure",
                "Build a structure from a blueprint, a generator or a saved blueprint, taking missing materials from nearby storage.",
                buildSchema(),
                (c, a) -> {
                    ServerLevel level = (ServerLevel) c.level();
                    ParseResult parsed = BlueprintResolver.resolve(a, level, author(c));
                    if (parsed.blueprint().isEmpty()) {
                        throw new IllegalArgumentException(String.join("; ", parsed.errors()));
                    }
                    Blueprint blueprint = parsed.blueprint().get();
                    Placement.PlacedBlueprint placed =
                            Placement.of(blueprint, origin(a), facing(a), c);
                    return new BuildTask(blueprint, placed.origin(), placed.rotation(), clearSite(a), gather(a));
                },
                true));
        registry.register(ToolKit.instantTool("save_blueprint",
                "Save a blueprint under a name so it can be built again later.",
                SchemaBuilder.object()
                        .string("name", "Name of the blueprint: a-z, 0-9, _ and -, at most 32 characters")
                        .string("blueprint", "Blueprint JSON; defaults to the last previewed structure")
                        .bool("overwrite", "Replace an existing blueprint with the same name")
                        .required("name")
                        .build(),
                BuildTools::save));
        registry.register(ToolKit.instantTool("list_blueprints",
                "List saved blueprints with their sizes and authors, and structure templates matching the query.",
                SchemaBuilder.object()
                        .string("query", "Only blueprint names and template ids containing this text")
                        .build(),
                BuildTools::list));
    }

    private static SchemaBuilder sourceSchema() {
        return SchemaBuilder.object()
                .string("blueprint", "Blueprint JSON; blueprint_help explains the format")
                .string("generator", "Generator JSON, for example {\"type\":\"house\",\"width\":7,\"depth\":7}")
                .string("saved", "Name of a saved blueprint")
                .string("structure",
                        "Structure template id, for example minecraft:village/plains/houses/plains_small_house_1")
                .integer("x", "Origin X; give all of x, y, z or none for 3 blocks in front of the owner")
                .integer("y", "Origin Y")
                .integer("z", "Origin Z")
                .enumeration("facing", "The direction the structure's front faces", "north", "south", "east", "west");
    }

    private static JsonObject buildSchema() {
        return sourceSchema()
                .bool("clear_site", "Break natural ground on the site (default true)")
                .bool("gather", "Obtain missing materials with obtain_item instead of failing (default false)")
                .build();
    }

    private static ToolResult preview(CompanionEntity companion, JsonObject args) {
        ServerLevel level = (ServerLevel) companion.level();
        ParseResult parsed = BlueprintResolver.resolve(args, level, author(companion));
        if (parsed.blueprint().isEmpty()) {
            return ToolResult.error(String.join("; ", parsed.errors()));
        }
        Blueprint blueprint = parsed.blueprint().get();
        Placement.PlacedBlueprint placed;
        Site.SiteReport site;
        Map<Item, Integer> needed;
        try {
            placed = Placement.of(blueprint, origin(args), facing(args), companion);
            site = Site.check(level, placed, companion, true);
            needed = Materials.needed(placed.specs(), site.foundation());
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        }
        companion.setLastPreview(blueprint);
        companion.owner().ifPresent(owner -> companion.showOutline(new PreviewOutline(owner.getUUID(), placed, blueprint)));
        return ToolResult.ok(previewText(
                blueprint, placed, site, needed, Materials.have(companion, level), Materials.carried(companion),
                parsed.notes()));
    }

    private static String previewText(
            Blueprint blueprint,
            Placement.PlacedBlueprint placed,
            Site.SiteReport site,
            Map<Item, Integer> needed,
            Map<Item, Integer> have,
            Map<Item, Integer> carried,
            List<String> notes) {
        long blocks = placed.specs().stream().filter(spec -> !spec.wanted().isAir()).count();
        return blueprint.name() + " " + blueprint.width() + "×" + blueprint.height() + "×" + blueprint.depth()
                + " at " + coords(placed.origin())
                + " facing " + Placement.facingFor(placed.rotation()).getSerializedName()
                + ": " + blocks + " blocks to place, "
                + site.toClear().size() + " to clear, "
                + site.foundation().size() + " foundation; materials: "
                + materialsText(needed, have, carried)
                + "; site: " + (site.problems().isEmpty() ? "clear" : String.join("; ", site.problems()))
                + (notes.isEmpty() ? "" : "; " + String.join("; ", notes));
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
            text.append(entry.getValue()).append(" × ").append(BuiltInRegistries.ITEM.getKey(item))
                    .append(" (have ").append(atHand);
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
        return ToolResult.ok("saved blueprint " + name + " (" + blueprint.blocks().size() + " blocks)");
    }

    private static ToolResult list(CompanionEntity companion, JsonObject args) {
        String query = present(args, "query") ? args.get("query").getAsString().toLowerCase(Locale.ROOT) : "";
        ServerLevel level = (ServerLevel) companion.level();
        List<BlueprintStore.Entry> entries = BlueprintStore.forLevel(level).list().stream()
                .filter(entry -> entry.name().toLowerCase(Locale.ROOT).contains(query))
                .toList();
        List<String> templates = level.getServer().getStructureTemplateManager().listTemplates()
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
                        .append(' ').append(entry.width()).append('×').append(entry.height()).append('×')
                        .append(entry.depth())
                        .append(" by ").append(authorName(level, entry.author()));
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
            return ToolResult.ok(query.isEmpty()
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

    private static BlockPos origin(JsonObject args) {
        return ToolKit.optionalPos(args).orElse(null);
    }

    private static Direction facing(JsonObject args) {
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
