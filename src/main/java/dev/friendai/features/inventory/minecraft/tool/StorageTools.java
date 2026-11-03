package dev.friendai.features.inventory.minecraft.tool;

import com.google.gson.JsonObject;
import dev.friendai.agent.tools.Cost;
import dev.friendai.agent.tools.ToolGroup;
import dev.friendai.agent.tools.ToolMeta;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.SchemaBuilder;
import dev.friendai.execution.tool.Tool;
import dev.friendai.execution.tool.ToolResult;
import dev.friendai.features.inventory.minecraft.storage.StorageAccess;
import dev.friendai.features.inventory.minecraft.task.CheckContainerTask;
import dev.friendai.features.inventory.minecraft.task.DepositTask;
import dev.friendai.features.inventory.minecraft.task.MoveItemsTask;
import dev.friendai.features.inventory.minecraft.task.StoreItemsTask;
import dev.friendai.features.inventory.minecraft.task.TakeItemsTask;
import dev.friendai.features.storage.StorageText;
import dev.friendai.features.storage.StoredCandidate;
import dev.friendai.server.execution.tools.FeatureToolContributor;
import dev.friendai.server.execution.tools.FeatureToolRegistry;
import dev.friendai.server.execution.tools.TaskToolDefinition;
import dev.friendai.task.inventory.ItemMatcher;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import net.minecraft.core.BlockPos;

/** Moving items between the companion's inventory, storage containers and remembered places. */
public final class StorageTools implements FeatureToolContributor {
    private static final int MAX_ROWS = 32;
    private static final JsonObject FIND_SCHEMA = SchemaBuilder.object()
            .string("item", "Item id like minecraft:iron_ingot, or a tag like #minecraft:planks")
            .integer("count", "At least this many in one container", 1, 2304)
            .bool("fresh_only", "Skip records older than the configured staleness limit")
            .required("item")
            .build();

    private final StorageAccess access;

    public StorageTools(StorageAccess access) {
        this.access = Objects.requireNonNull(access, "storage access");
    }

    @Override
    public void register(FeatureToolRegistry registry) {
        registry.registerTask(task(
                "store_items",
                "Put items from the companion's inventory into a chest, barrel or other storage container. Without item, stores everything. One call stores up to 2304 items: give item and count for two stacks instead of calling once per stack.",
                SchemaBuilder.object()
                        .string("item", "Item id like minecraft:cobblestone, or a tag like #minecraft:planks")
                        .integer("count", "How many items to store", 1, 2304)
                        .integer("x", "Container X coordinate")
                        .integer("y", "Container Y coordinate")
                        .integer("z", "Container Z coordinate")
                        .bool("placed", "Use the nearest storage container you placed yourself")
                        .build(),
                (c, a) -> {
                    ItemMatcher matcher = a.has("item") && !a.get("item").isJsonNull()
                            ? matcher(a.get("item").getAsString())
                            : null;
                    int count = a.has("count") && !a.get("count").isJsonNull()
                            ? a.get("count").getAsInt()
                            : Integer.MAX_VALUE;
                    boolean placed = a.has("placed")
                            && !a.get("placed").isJsonNull()
                            && a.get("placed").getAsBoolean();
                    return new StoreItemsTask(matcher, count, optionalPos(a), placed);
                }));
        registry.registerTask(task(
                "take_items",
                "Take items from a chest, barrel or other storage container into the companion's inventory.",
                SchemaBuilder.object()
                        .string("item", "Item id like minecraft:iron_ingot, or a tag like #minecraft:planks")
                        .integer("count", "How many items to take", 1, 2304)
                        .integer("x", "Container X coordinate")
                        .integer("y", "Container Y coordinate")
                        .integer("z", "Container Z coordinate")
                        .required("item", "count")
                        .build(),
                (c, a) -> new TakeItemsTask(
                        matcher(a.get("item").getAsString()), a.get("count").getAsInt(), optionalPos(a))));
        registry.registerTask(task(
                        "check_container",
                        "Walk to a chest, barrel or other storage container and report what is inside.",
                        SchemaBuilder.object()
                                .integer("x", "Container X coordinate")
                                .integer("y", "Container Y coordinate")
                                .integer("z", "Container Z coordinate")
                                .build(),
                        (c, a) -> new CheckContainerTask(optionalPos(a)))
                .asCreativeSafe());
        registry.registerTask(task(
                "deposit_items",
                "Unload loot into the chests of a remembered storage place (or home), sorting like with like. Keeps the best tool of each kind, some food and building blocks, and the server's keep list.",
                SchemaBuilder.object()
                        .string("place", "Name of the storage place or home; the nearest storage place when omitted")
                        .build(),
                (c, a) -> new DepositTask(
                        a.has("place") && !a.get("place").isJsonNull()
                                ? a.get("place").getAsString()
                                : null)));
        registry.registerTask(new TaskToolDefinition(
                "move_items",
                "Move a known quantity of one item from one storage container to another in one call, returning "
                        + "leftovers to the source.",
                SchemaBuilder.object()
                        .string("item", "Item id like minecraft:iron_ingot, or a tag like #minecraft:planks")
                        .integer("count", "How many items to move", 1, 2304)
                        .integer("from_x", "Source container X")
                        .integer("from_y", "Source container Y")
                        .integer("from_z", "Source container Z")
                        .integer("to_x", "Target container X")
                        .integer("to_y", "Target container Y")
                        .integer("to_z", "Target container Z")
                        .required("item", "count", "from_x", "from_y", "from_z", "to_x", "to_y", "to_z")
                        .build(),
                (c, a) -> new MoveItemsTask(
                        matcher(a.get("item").getAsString()),
                        a.get("count").getAsInt(),
                        new BlockPos(
                                a.get("from_x").getAsInt(),
                                a.get("from_y").getAsInt(),
                                a.get("from_z").getAsInt()),
                        new BlockPos(
                                a.get("to_x").getAsInt(),
                                a.get("to_y").getAsInt(),
                                a.get("to_z").getAsInt())),
                null,
                true,
                AccessLevel.TRUSTED,
                TaskToolDefinition.QueueTraits.DEFAULT,
                false,
                false,
                metadata("move_items"),
                null,
                null));
        registry.registerTool(findStoredItem(access), metadata("find_stored_item"));
    }

    static Tool<CompanionEntity> findStoredItem(StorageAccess access) {
        return new Tool<>() {
            @Override
            public String name() {
                return "find_stored_item";
            }

            @Override
            public String description() {
                return "Report where you last saw an item in a container you opened. Use it before take_items or "
                        + "store_items when you do not know which container holds the item.";
            }

            @Override
            public JsonObject schema() {
                return FIND_SCHEMA;
            }

            @Override
            public ToolResult execute(CompanionEntity companion, JsonObject args) {
                String label = args.has("item") && !args.get("item").isJsonNull()
                        ? args.get("item").getAsString()
                        : "";
                Set<String> ids = access.itemIds(label);
                if (ids.isEmpty()) {
                    return ToolResult.error(StorageText.unknown(label));
                }
                int minCount = args.has("count") && !args.get("count").isJsonNull()
                        ? Math.max(1, args.get("count").getAsInt())
                        : 1;
                boolean freshOnly = args.has("fresh_only")
                        && !args.get("fresh_only").isJsonNull()
                        && args.get("fresh_only").getAsBoolean();
                List<StoredCandidate> all = access.find(companion, ids, minCount, access.nowTick(companion));
                if (all.isEmpty()) {
                    return ToolResult.ok(StorageText.noRecord(label));
                }
                List<StoredCandidate> rows = freshOnly
                        ? all.stream().filter(candidate -> !candidate.stale()).toList()
                        : all;
                if (rows.isEmpty()) {
                    return ToolResult.ok(StorageText.foundFreshOnly(label, all));
                }
                int total = rows.stream().mapToInt(StoredCandidate::count).sum();
                List<StoredCandidate> capped = rows.size() > MAX_ROWS ? rows.subList(0, MAX_ROWS) : rows;
                return ToolResult.ok(StorageText.found(label, total, capped));
            }
        };
    }

    private static TaskToolDefinition task(
            String name, String description, JsonObject schema, TaskToolDefinition.Factory factory) {
        return new TaskToolDefinition(name, description, schema, factory, true, metadata(name));
    }

    private static ToolMeta metadata(String name) {
        if (name.equals("move_items")) {
            return new ToolMeta(
                    name,
                    ToolGroup.STORAGE,
                    false,
                    "",
                    List.of(),
                    Cost.MEDIUM);
        }
        if (name.equals("find_stored_item")) {
            return new ToolMeta(
                    name,
                    ToolGroup.STORAGE,
                    false,
                    "",
                    List.of(),
                    Cost.LOW);
        }
        String hint = name.equals("store_items")
                ? "- When the player says to store it again in the container you placed, pass placed:true; it uses the nearest container from your placement record."
                : name.equals("deposit_items")
                        ? "- To unload loot at the base, use deposit_items with the storage place name; it keeps your tools and food. Use store_items only for one container or one item."
                        : "";
        return new ToolMeta(name, ToolGroup.STORAGE, false, hint, List.of(), Cost.MEDIUM);
    }

    private static ItemMatcher matcher(String idOrTag) {
        return ItemMatcher.parse(idOrTag)
                .orElseThrow(() -> new IllegalArgumentException("unknown item or tag: " + idOrTag));
    }

    private static net.minecraft.core.BlockPos optionalPos(JsonObject args) {
        int present = 0;
        for (String name : new String[] {"x", "y", "z"}) {
            if (args.has(name) && !args.get(name).isJsonNull()) present++;
        }
        if (present == 0) return null;
        if (present != 3) throw new IllegalArgumentException("give all of x, y, z or none");
        return new net.minecraft.core.BlockPos(
                args.get("x").getAsInt(),
                args.get("y").getAsInt(),
                args.get("z").getAsInt());
    }
}
