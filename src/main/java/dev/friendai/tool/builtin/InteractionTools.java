package dev.friendai.tool.builtin;

import com.google.gson.JsonObject;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.task.Task;
import dev.friendai.task.builtin.InteractEntityTask;
import dev.friendai.task.builtin.MountTask;
import dev.friendai.task.builtin.SleepTask;
import dev.friendai.task.builtin.ThrowItemTask;
import dev.friendai.task.interact.EntityTargets;
import dev.friendai.tool.SchemaBuilder;
import dev.friendai.tool.ToolRegistry;
import dev.friendai.tool.ToolResult;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/** Simple contact with the world: animals, vehicles, beds, tossing things. */
final class InteractionTools {
    private InteractionTools() {}

    static void register(ToolRegistry<CompanionEntity> registry, List<TaskToolDef> defs) {
        ToolKit.add(registry, defs, new TaskToolDef("interact_entity",
                "Walk up to an animal and use your hand or an item on it: pet it, feed it, put a name tag on it. "
                        + "Only animals that are free to touch, never someone's pet.",
                SchemaBuilder.object()
                        .string("target", "The animal type, such as cow, horse or minecraft:sheep (the nearest one)")
                        .string("item", "An item from your inventory to use on it, such as wheat; empty hand when omitted")
                        .required("target")
                        .build(),
                InteractionTools::interactEntity, true));
        ToolKit.add(registry, defs, new TaskToolDef("mount",
                "Walk up to a boat, minecart or rideable animal and get on it.",
                SchemaBuilder.object()
                        .string("target", "The vehicle or mount type, such as boat, horse or minecart (the nearest one)")
                        .required("target")
                        .build(),
                (c, a) -> new MountTask(named(c, a.get("target").getAsString())), true));
        registry.register(ToolKit.instantTool("dismount",
                "Get off whatever you are riding.",
                (c, a) -> {
                    if (!c.isPassenger()) {
                        return ToolResult.error("I'm not riding anything");
                    }
                    String vehicle = EntityTargets.label(c.getVehicle());
                    c.stopRiding();
                    return ToolResult.ok("got off the " + vehicle);
                }));
        ToolKit.add(registry, defs, new TaskToolDef("sleep",
                "Walk to a bed and sleep in it. By default you get up in the morning and refuse to sleep in the daytime; "
                        + "pass until_morning=false to sleep until told to get up.",
                SchemaBuilder.object()
                        .integer("x", "Bed X coordinate; the nearest bed when omitted")
                        .integer("y", "Bed Y coordinate")
                        .integer("z", "Bed Z coordinate")
                        .bool("until_morning", "Get up when the day comes; default true")
                        .build(),
                (c, a) -> new SleepTask(ToolKit.optionalPos(a).orElse(null),
                        !a.has("until_morning") || a.get("until_morning").getAsBoolean()), true));
        ToolKit.add(registry, defs, new TaskToolDef("throw_item",
                "Toss items from your inventory toward someone or something (owner, a player's name, an animal/mob type), "
                        + "toward x, y, z, or straight ahead. They land as ordinary items on the ground.",
                SchemaBuilder.object()
                        .string("item", "Item id or tag to throw")
                        .integer("count", "How many to throw (1 to 64); default 1", 1, 64)
                        .string("target", "owner, a player name, or an entity type such as cow")
                        .number("x", "Aim at this X coordinate")
                        .number("y", "Aim at this Y coordinate")
                        .number("z", "Aim at this Z coordinate")
                        .required("item")
                        .build(),
                InteractionTools::throwItem, true));
    }

    private static Task interactEntity(CompanionEntity c, JsonObject a) {
        Entity target = named(c, a.get("target").getAsString());
        var item = a.has("item") ? ToolKit.parseItemMatcher(a.get("item").getAsString()) : null;
        return new InteractEntityTask(target, item, c);
    }

    private static Task throwItem(CompanionEntity c, JsonObject a) {
        var matcher = ToolKit.parseItemMatcher(a.get("item").getAsString());
        int count = a.has("count") ? a.get("count").getAsInt() : 1;
        Supplier<Vec3> aim = null;
        String label = "";
        if (a.has("target")) {
            Entity target = named(c, a.get("target").getAsString());
            aim = () -> target.isAlive() && !target.isRemoved() ? target.getEyePosition() : null;
            label = EntityTargets.label(target);
        } else if (a.has("x") && a.has("y") && a.has("z")) {
            Vec3 fixed = new Vec3(a.get("x").getAsDouble(), a.get("y").getAsDouble(), a.get("z").getAsDouble());
            aim = () -> fixed;
            label = (int) Math.floor(fixed.x) + " " + (int) Math.floor(fixed.y) + " " + (int) Math.floor(fixed.z);
        }
        return new ThrowItemTask(matcher, count, aim, label);
    }

    private static Entity named(CompanionEntity c, String name) {
        return EntityTargets.find(c, name).orElseThrow(() -> new IllegalArgumentException("I can't see any " + name));
    }
}
