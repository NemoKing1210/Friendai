package dev.friendai.features.construction.minecraft.tool;

import com.google.gson.JsonObject;
import dev.friendai.agent.tools.ToolGroup;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.tool.SchemaBuilder;
import dev.friendai.execution.tool.ToolResult;
import dev.friendai.features.construction.minecraft.build.Placement;
import dev.friendai.features.construction.minecraft.task.BuildTask;
import dev.friendai.features.construction.minecraft.task.SpecialStructureTask;
import dev.friendai.features.construction.special.SpecialStructure;
import dev.friendai.features.construction.special.StructureBook;
import dev.friendai.features.construction.special.StructurePlan;
import dev.friendai.features.construction.special.StructurePlanner;
import dev.friendai.features.construction.special.StructureSnapshot;
import dev.friendai.features.construction.special.Trigger;
import dev.friendai.server.execution.tools.FeatureToolRegistry;
import dev.friendai.task.TaskContext;
import dev.friendai.task.structure.StructureSite;
import dev.friendai.task.structure.StructureSnapshotBuilder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;

/** Special structures that come alive when finished: iron golem, snow golem and nether portal. */
final class StructureTools {
    private static final StructureBook BOOK = StructureBook.withBuiltins();

    private StructureTools() {}

    static void register(FeatureToolRegistry registry, BuildTask.ObtainFactory obtainFactory) {
        ConstructionToolSupport.registerTask(
                registry,
                ToolGroup.STRUCTURE,
                "build_special",
                "Build a special structure and bring it to life: an iron golem, a snow golem or a lit nether portal. "
                        + "The body blocks are gathered if missing; the finishing item (a carved pumpkin or flint and steel) "
                        + "must already be in the inventory. Use plan_special first to see what is needed.",
                schema(),
                (c, a) -> {
                    SpecialStructure structure = structure(a);
                    StructureSite site = site(c, structure, a);
                    String missing = missingFinisher(c, structure, site);
                    if (missing != null) {
                        throw new IllegalArgumentException(
                                "I need " + missing + " in the inventory first; get it with obtain_item");
                    }
                    return new SpecialStructureTask(
                            structure, site.origin(), site.rotation(), maxNearbySpawned(c), obtainFactory);
                },
                true);
        ConstructionToolSupport.registerTool(
                registry,
                ToolGroup.STRUCTURE,
                "plan_special",
                "Check whether a special structure can be built here and what it would take, without building it.",
                schema(),
                (c, a) -> {
                    SpecialStructure structure;
                    StructureSite site;
                    try {
                        structure = structure(a);
                        site = site(c, structure, a);
                    } catch (IllegalArgumentException e) {
                        return ToolResult.error(e.getMessage());
                    }
                    StructureSnapshot snapshot = snapshot(c, structure, site);
                    StructurePlan plan = StructurePlanner.plan(structure, snapshot);
                    return ToolResult.ok(plan.describe());
                });
    }

    private static com.google.gson.JsonObject schema() {
        return SchemaBuilder.object()
                .enumeration("kind", "Which structure to build", BOOK.ids().toArray(String[]::new))
                .integer(
                        "x",
                        "Origin X (minimum corner); give all of x, y, z or none for 3 blocks in front of the owner")
                .integer("y", "Origin Y")
                .integer("z", "Origin Z")
                .enumeration("facing", "The direction the structure's front faces", "north", "south", "east", "west")
                .required("kind")
                .build();
    }

    private static SpecialStructure structure(JsonObject args) {
        String kind = args.get("kind").getAsString();
        return BOOK.get(kind)
                .orElseThrow(() -> new IllegalArgumentException(
                        "unknown kind " + kind + "; valid kinds: " + String.join(", ", BOOK.ids())));
    }

    private static StructureSite site(CompanionEntity companion, SpecialStructure structure, JsonObject args) {
        Placement.PlacedBlueprint placed =
                Placement.of(structure.body(), BuildTools.origin(args), BuildTools.facing(args), companion);
        return new StructureSite(placed.origin(), placed.rotation());
    }

    private static StructureSnapshot snapshot(
            CompanionEntity companion, SpecialStructure structure, StructureSite site) {
        return StructureSnapshotBuilder.build(
                (ServerLevel) companion.level(), companion, structure, site, maxNearbySpawned(companion));
    }

    private static int maxNearbySpawned(CompanionEntity companion) {
        return new TaskContext(companion, (ServerLevel) companion.level())
                .settings()
                .maxNearbySpawned();
    }
    /** The finishing item the bag lacks, or null when it is there. */
    private static String missingFinisher(CompanionEntity companion, SpecialStructure structure, StructureSite site) {
        String item = switch (structure.trigger()) {
            case Trigger.LastBlock t ->
                BuiltInRegistries.ITEM
                        .getKey(site.wanted(t.state()).getBlock().asItem())
                        .toString();
            case Trigger.Ignite t -> t.item();
        };
        return snapshot(companion, structure, site).bag().getOrDefault(item, 0) > 0 ? null : item;
    }
}
