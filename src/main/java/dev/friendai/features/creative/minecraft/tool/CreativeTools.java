package dev.friendai.features.creative.minecraft.tool;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.friendai.agent.tools.Cost;
import dev.friendai.agent.tools.ToolGroup;
import dev.friendai.agent.tools.ToolMeta;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.execution.tool.SchemaBuilder;
import dev.friendai.features.creative.CreativeItemCount;
import dev.friendai.features.creative.minecraft.task.CreateItemsTask;
import dev.friendai.server.execution.tools.FeatureToolContributor;
import dev.friendai.server.execution.tools.FeatureToolRegistry;
import dev.friendai.server.execution.tools.TaskToolDefinition;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Owner-only creation of bounded item deliveries while the companion has creative authority. */
public final class CreativeTools implements FeatureToolContributor {
    public CreativeTools() {}

    @Override
    public void register(FeatureToolRegistry registry) {
        registry.registerTask(new TaskToolDefinition(
                "create_item",
                "Create registered items with their default components. Delivered items remain after creative mode is turned off. "
                        + "The destination is your inventory unless owner is requested; the owner must be online within "
                        + CreateItemsTask.OWNER_DELIVERY_RADIUS + " blocks in the same dimension.",
                SchemaBuilder.object()
                        .string("item", "Registered item id, for example minecraft:iron_ingot")
                        .integer(
                                "count",
                                "Exact positive number of items to create; delivery is bounded by inventory space")
                        .enumeration(
                                "destination",
                                "Where to deliver items: self (default) or owner nearby",
                                "self",
                                "owner")
                        .required("item", "count")
                        .build(),
                CreativeTools::createTask,
                CreativeTools::createTask,
                false,
                AccessLevel.OWNER,
                TaskToolDefinition.QueueTraits.DEFAULT,
                true,
                true,
                metadata("create_item")));
    }

    private static CreateItemsTask createTask(CompanionEntity companion, Caller caller, JsonObject args) {
        if (!companion.creativeMode()) {
            throw new IllegalArgumentException("creative mode must be active to create items");
        }
        String rawItem = args.get("item").getAsString().strip();
        if (rawItem.isEmpty()) {
            throw new IllegalArgumentException("item id must not be empty");
        }
        Identifier id = Identifier.tryParse(rawItem.contains(":") ? rawItem : "minecraft:" + rawItem);
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
            throw new IllegalArgumentException("unknown registered item: " + rawItem);
        }
        JsonElement countValue = args.get("count");
        if (countValue == null
                || !countValue.isJsonPrimitive()
                || !countValue.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("count must be an exact positive integer representable as a long");
        }
        long count = CreativeItemCount.parsePositiveLong(countValue.getAsString());
        Item item = BuiltInRegistries.ITEM.getValue(id);
        ItemStack prototype = new ItemStack(item);
        if (prototype.isEmpty()) {
            throw new IllegalArgumentException("air is not an item that can be created");
        }
        String rawDestination =
                args.has("destination") ? args.get("destination").getAsString() : "self";
        CreateItemsTask.Destination destination = switch (rawDestination) {
            case "self" -> CreateItemsTask.Destination.SELF;
            case "owner" -> CreateItemsTask.Destination.OWNER;
            default -> throw new IllegalArgumentException("destination must be self or owner");
        };
        return new CreateItemsTask(prototype, count, destination);
    }

    private static CreateItemsTask createTask(CompanionEntity companion, JsonObject args) {
        return createTask(companion, null, args);
    }

    private static ToolMeta metadata(String name) {
        return new ToolMeta(
                name,
                ToolGroup.CONTROL,
                false,
                "- In creative mode, use create_item for resources instead of mining unnecessarily. It reports how many fit and keeps work bounded; choose owner only when the owner is nearby in the same dimension.",
                List.of(),
                Cost.LOW);
    }
}
