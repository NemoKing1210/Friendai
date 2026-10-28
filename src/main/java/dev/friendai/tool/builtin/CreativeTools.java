package dev.friendai.tool.builtin;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.execution.tool.SchemaBuilder;
import dev.friendai.execution.tool.ToolRegistry;
import dev.friendai.features.creative.CreativeCommandPolicy;
import dev.friendai.features.creative.CreativeItemCount;
import dev.friendai.task.builtin.CreateItemsTask;
import dev.friendai.task.builtin.ExecuteCommandTask;
import dev.friendai.task.builtin.FlyToTask;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/** Owner-only tools that spend an explicitly authorized creative capability. */
final class CreativeTools {
    private CreativeTools() {}

    static void register(ToolRegistry<CompanionEntity> registry, List<TaskToolDef> defs) {
        JsonObject schema = SchemaBuilder.object()
                .string("item", "Registered item id, for example minecraft:iron_ingot")
                .integer("count", "Exact positive number of items to create; delivery is bounded by inventory space")
                .enumeration("destination", "Where to deliver items: self (default) or owner nearby", "self", "owner")
                .required("item", "count")
                .build();
        TaskToolDef def = new TaskToolDef(
                "create_item",
                "Create registered items with their default components. Delivered items remain after creative mode is turned off. "
                        + "The destination is your inventory unless owner is requested; the owner must be online within "
                        + CreateItemsTask.OWNER_DELIVERY_RADIUS + " blocks in the same dimension.",
                schema,
                CreativeTools::createTask,
                false,
                AccessLevel.OWNER,
                TaskToolDef.QueueTraits.DEFAULT,
                CreativeTools::createTask);
        ToolKit.addCaller(
                registry,
                defs,
                def,
                (companion, caller, args) ->
                        ToolKit.startTask(companion, "create_item", createTask(companion, caller, args), caller));

        TaskToolDef flight = new TaskToolDef(
                "fly_to",
                "Fly to coordinates in the current dimension using a bounded route that respects collisions, world bounds, and loaded chunks.",
                SchemaBuilder.object()
                        .number("x", "Target X coordinate")
                        .number("y", "Target Y coordinate")
                        .number("z", "Target Z coordinate")
                        .required("x", "y", "z")
                        .build(),
                CreativeTools::flyTask,
                false,
                AccessLevel.OWNER,
                TaskToolDef.QueueTraits.DEFAULT,
                CreativeTools::flyTask);
        ToolKit.addCaller(
                registry,
                defs,
                flight,
                (companion, caller, args) -> ToolKit.startTask(companion, "fly_to", flyTask(companion, args), caller));

        TaskToolDef command = new TaskToolDef(
                "execute_command",
                "Run one allowlisted gameplay command using only the online owner's normal command permissions. The companion is the command entity and @s; only teleport, time, weather, give-to-owner, effect-on-self, and enchant-owner forms are accepted. execute/function/schedule and other roots are denied. Feedback is bounded.",
                SchemaBuilder.object()
                        .string("command", "One command, with or without one leading slash")
                        .required("command")
                        .build(),
                CreativeTools::commandTask,
                false,
                AccessLevel.OWNER,
                TaskToolDef.QueueTraits.DEFAULT,
                CreativeTools::commandTask);
        ToolKit.addCaller(
                registry,
                defs,
                command,
                (companion, caller, args) ->
                        ToolKit.startTask(companion, "execute_command", commandTask(companion, args), caller));
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

    private static FlyToTask flyTask(CompanionEntity companion, Caller caller, JsonObject args) {
        if (!companion.creativeMode()) {
            throw new IllegalArgumentException("creative mode must be active to fly");
        }
        double x = coordinate(args, "x");
        double y = coordinate(args, "y");
        double z = coordinate(args, "z");
        if (y < companion.level().getMinY() || y > companion.level().getMaxY()) {
            throw new IllegalArgumentException("target y must be within the current dimension height");
        }
        return new FlyToTask(new Vec3(x, y, z));
    }

    private static FlyToTask flyTask(CompanionEntity companion, JsonObject args) {
        return flyTask(companion, null, args);
    }

    private static ExecuteCommandTask commandTask(CompanionEntity companion, Caller caller, JsonObject args) {
        if (!companion.creativeMode()) {
            throw new IllegalArgumentException("creative mode must be active to run commands");
        }
        ServerPlayer owner = companion
                .owner()
                .orElseThrow(() -> new IllegalArgumentException("the owner must be online to run commands"));
        if (caller == null || !owner.getUUID().equals(caller.uuid())) {
            throw new IllegalArgumentException("only the owner may run commands");
        }
        String raw = args.has("command") ? args.get("command").getAsString() : "";
        CreativeCommandPolicy.Decision decision =
                CreativeCommandPolicy.evaluate(raw, owner.getName().getString());
        if (!decision.allowed()) throw new IllegalArgumentException(decision.reason());
        return new ExecuteCommandTask(decision.command());
    }

    private static ExecuteCommandTask commandTask(CompanionEntity companion, JsonObject args) {
        ServerPlayer owner = companion
                .owner()
                .orElseThrow(() -> new IllegalArgumentException("the owner must be online to run commands"));
        return commandTask(
                companion, new Caller(owner.getUUID(), owner.getName().getString(), AccessLevel.OWNER), args);
    }

    private static double coordinate(JsonObject args, String field) {
        JsonElement value = args.get(field);
        if (value == null
                || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(field + " must be a finite number");
        }
        double number = value.getAsDouble();
        if (!Double.isFinite(number)) throw new IllegalArgumentException(field + " must be a finite number");
        return number;
    }
}
