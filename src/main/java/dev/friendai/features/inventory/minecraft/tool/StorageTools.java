package dev.friendai.features.inventory.minecraft.tool;

import com.google.gson.JsonObject;
import dev.friendai.agent.tools.ToolCatalog;
import dev.friendai.agent.tools.ToolMeta;
import dev.friendai.execution.tool.SchemaBuilder;
import dev.friendai.features.inventory.minecraft.task.CheckContainerTask;
import dev.friendai.features.inventory.minecraft.task.DepositTask;
import dev.friendai.features.inventory.minecraft.task.StoreItemsTask;
import dev.friendai.features.inventory.minecraft.task.TakeItemsTask;
import dev.friendai.server.execution.tools.FeatureToolContributor;
import dev.friendai.server.execution.tools.FeatureToolRegistry;
import dev.friendai.server.execution.tools.TaskToolDefinition;
import dev.friendai.task.inventory.ItemMatcher;

/** Moving items between the companion's inventory, storage containers and remembered places. */
public final class StorageTools implements FeatureToolContributor {
    public StorageTools() {}

    @Override
    public void register(FeatureToolRegistry registry) {
        registry.registerTask(task(
                "store_items",
                "Put items from the companion's inventory into a chest, barrel or other storage container. Without item, stores everything.",
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
    }

    private static TaskToolDefinition task(
            String name, String description, JsonObject schema, TaskToolDefinition.Factory factory) {
        return new TaskToolDefinition(name, description, schema, factory, true, metadata(name));
    }

    private static ToolMeta metadata(String name) {
        return ToolCatalog.defaults()
                .meta(name)
                .orElseThrow(() -> new IllegalArgumentException("missing tool metadata: " + name));
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
