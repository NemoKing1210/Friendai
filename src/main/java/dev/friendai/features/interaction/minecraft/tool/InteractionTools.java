package dev.friendai.features.interaction.minecraft.tool;

import com.google.gson.JsonObject;
import dev.friendai.agent.tools.Cost;
import dev.friendai.agent.tools.ToolGroup;
import dev.friendai.agent.tools.ToolMeta;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.tool.SchemaBuilder;
import dev.friendai.execution.tool.Tool;
import dev.friendai.execution.tool.ToolResult;
import dev.friendai.features.interaction.minecraft.task.InteractEntityTask;
import dev.friendai.features.interaction.minecraft.task.MountTask;
import dev.friendai.features.navigation.minecraft.nav.EntityTargets;
import dev.friendai.server.execution.tools.FeatureToolContributor;
import dev.friendai.server.execution.tools.FeatureToolRegistry;
import dev.friendai.server.execution.tools.TaskToolDefinition;
import dev.friendai.task.Task;
import dev.friendai.task.builtin.SleepTask;
import dev.friendai.task.inventory.ItemMatcher;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Simple contact with the world: animals, vehicles, beds, tossing things. */
public final class InteractionTools implements FeatureToolContributor {
    public InteractionTools() {}

    @Override
    public void register(FeatureToolRegistry registry) {
        registerTask(
                registry,
                "interact_entity",
                "Walk up to an animal and use your hand or an item on it: pet it, feed it, put a name tag on it. "
                        + "Only animals that are free to touch, never someone's pet.",
                SchemaBuilder.object()
                        .string("target", "The animal type, such as cow, horse or minecraft:sheep (the nearest one)")
                        .string(
                                "item",
                                "An item from your inventory to use on it, such as wheat; empty hand when omitted")
                        .required("target")
                        .build(),
                InteractionTools::interactEntity,
                true);
        registerTask(
                registry,
                "mount",
                "Walk up to a boat, minecart or rideable animal and get on it.",
                SchemaBuilder.object()
                        .string(
                                "target",
                                "The vehicle or mount type, such as boat, horse or minecart (the nearest one)")
                        .required("target")
                        .build(),
                (c, a) -> new MountTask(named(c, a.get("target").getAsString())),
                true);
        registerInstant(registry, "dismount", "Get off whatever you are riding.", (c, a) -> {
            if (!c.isPassenger()) {
                return ToolResult.error("I'm not riding anything");
            }
            String vehicle = EntityTargets.label(c.getVehicle());
            c.stopRiding();
            return ToolResult.ok("got off the " + vehicle);
        });
        registerTask(
                registry,
                "sleep",
                "Walk to a bed and sleep in it. By default you get up in the morning and refuse to sleep in the daytime; "
                        + "pass until_morning=false to sleep until told to get up.",
                SchemaBuilder.object()
                        .integer("x", "Bed X coordinate; the nearest bed when omitted")
                        .integer("y", "Bed Y coordinate")
                        .integer("z", "Bed Z coordinate")
                        .bool("until_morning", "Get up when the day comes; default true")
                        .build(),
                (c, a) -> new SleepTask(
                        optionalPos(a).orElse(null),
                        !a.has("until_morning") || a.get("until_morning").getAsBoolean()),
                true);
    }

    private static Task interactEntity(CompanionEntity c, JsonObject a) {
        Entity target = named(c, a.get("target").getAsString());
        var item = a.has("item") ? parseItemMatcher(a.get("item").getAsString()) : null;
        if (target instanceof Piglin && item != null && item.test(new ItemStack(Items.GOLD_INGOT))) {
            throw new IllegalArgumentException(
                    "generic tools cannot initiate piglin barter; a dedicated explicit bounded barter order is required");
        }
        return new InteractEntityTask(target, item, c);
    }

    private static Entity named(CompanionEntity c, String name) {
        return EntityTargets.find(c, name).orElseThrow(() -> new IllegalArgumentException("I can't see any " + name));
    }

    private static Optional<BlockPos> optionalPos(JsonObject args) {
        int present = 0;
        for (String name : new String[] {"x", "y", "z"}) {
            if (args.has(name) && !args.get(name).isJsonNull()) present++;
        }
        if (present == 0) return Optional.empty();
        if (present != 3) throw new IllegalArgumentException("give all of x, y, z or none");
        return Optional.of(new BlockPos(
                args.get("x").getAsInt(),
                args.get("y").getAsInt(),
                args.get("z").getAsInt()));
    }

    private static ItemMatcher parseItemMatcher(String idOrTag) {
        return ItemMatcher.parse(idOrTag)
                .orElseThrow(() -> new IllegalArgumentException("unknown item or tag: " + idOrTag));
    }

    private static void registerTask(
            FeatureToolRegistry registry,
            String name,
            String description,
            JsonObject schema,
            TaskToolDefinition.Factory factory,
            boolean plannable) {
        registry.registerTask(new TaskToolDefinition(
                name, description, schema, factory, plannable, TaskToolDefinition.QueueTraits.DEFAULT, metadata(name)));
    }

    private static void registerInstant(FeatureToolRegistry registry, String name, String description, Action action) {
        registry.registerTool(
                new Tool<>() {
                    @Override
                    public String name() {
                        return name;
                    }

                    @Override
                    public String description() {
                        return description;
                    }

                    @Override
                    public JsonObject schema() {
                        return SchemaBuilder.object().build();
                    }

                    @Override
                    public ToolResult execute(CompanionEntity companion, JsonObject args) {
                        return action.run(companion, args);
                    }
                },
                metadata(name));
    }

    private static ToolMeta metadata(String name) {
        return new ToolMeta(name, ToolGroup.INTERACTION, false, "", List.of(), Cost.MEDIUM);
    }

    @FunctionalInterface
    private interface Action {
        ToolResult run(CompanionEntity companion, JsonObject args);
    }
}
