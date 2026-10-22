package dev.friedai.tool.builtin;

import com.google.gson.JsonObject;
import dev.friedai.entity.CompanionEntity;
import dev.friedai.perception.PerceptionBuilder;
import dev.friedai.perception.PerceptionSnapshot;
import dev.friedai.task.Task;
import dev.friedai.task.builtin.FollowTask;
import dev.friedai.task.builtin.GiveItemsTask;
import dev.friedai.task.builtin.GoToTask;
import dev.friedai.task.builtin.MineBlocksTask;
import dev.friedai.task.builtin.PickupItemsTask;
import dev.friedai.task.builtin.StayTask;
import dev.friedai.task.mining.BlockMatcher;
import dev.friedai.tool.SchemaBuilder;
import dev.friedai.tool.Tool;
import dev.friedai.tool.ToolRegistry;
import dev.friedai.tool.ToolResult;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.IntSupplier;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/** The v1 skills, as tools the LLM can call. */
public final class FriedaiTools {
    private FriedaiTools() {}

    /** @param mineRadius search radius for {@code mine_blocks}, read on every call so config changes apply live */
    public static ToolRegistry<CompanionEntity> create(IntSupplier mineRadius) {
        return new ToolRegistry<CompanionEntity>()
                .register(taskTool("follow_player", "Follow the owner and stay close to them.",
                        SchemaBuilder.object().build(), (c, a) -> new FollowTask(false)))
                .register(taskTool("stay", "Stay in the current spot until told otherwise.",
                        SchemaBuilder.object().build(), (c, a) -> new StayTask()))
                .register(taskTool("go_to", "Walk to the given coordinates, or to the owner with target=owner.",
                        SchemaBuilder.object()
                                .number("x", "Target X coordinate")
                                .number("y", "Target Y coordinate")
                                .number("z", "Target Z coordinate")
                                .enumeration("target", "Use 'owner' to walk to the owner", "owner")
                                .build(),
                        FriedaiTools::goTo))
                .register(taskTool("mine_blocks", "Mine N blocks of the given block id or #tag near the companion and collect the drops.",
                        SchemaBuilder.object()
                                .string("block", "Block id like minecraft:oak_log, or a tag like #minecraft:logs")
                                .integer("count", "How many blocks to mine", 1, 64)
                                .required("block", "count")
                                .build(),
                        (c, a) -> {
                            String block = a.get("block").getAsString();
                            BlockMatcher matcher = BlockMatcher.parse(block)
                                    .orElseThrow(() -> new IllegalArgumentException("unknown block or tag: " + block));
                            return new MineBlocksTask(matcher, a.get("count").getAsInt(), mineRadius.getAsInt());
                        }))
                .register(taskTool("pickup_items", "Pick up dropped items within the given radius.",
                        SchemaBuilder.object().integer("radius", "Search radius in blocks", 1, 16).required("radius").build(),
                        (c, a) -> new PickupItemsTask(a.get("radius").getAsInt())))
                .register(taskTool("give_items", "Walk to the owner and give them items from the companion's inventory.",
                        SchemaBuilder.object()
                                .string("item", "Item id like minecraft:oak_log")
                                .integer("count", "How many items to give", 1, 64)
                                .required("item", "count")
                                .build(),
                        (c, a) -> {
                            String name = a.get("item").getAsString();
                            Item item = parseItem(name).orElseThrow(() -> new IllegalArgumentException("unknown item: " + name));
                            return new GiveItemsTask(item, a.get("count").getAsInt());
                        }))
                .register(instantTool("look_around", "Describe the surroundings in detail.",
                        (c, a) -> ToolResult.ok(PerceptionBuilder.detailed(c).toPromptText())))
                .register(instantTool("get_inventory", "List what the companion is carrying.", (c, a) -> {
                    Map<String, Integer> counts = new LinkedHashMap<>();
                    for (int i = 0; i < c.inventory().getContainerSize(); i++) {
                        ItemStack s = c.inventory().getItem(i);
                        if (!s.isEmpty()) {
                            String id = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
                            counts.merge(id, s.getCount(), Integer::sum);
                        }
                    }
                    return counts.isEmpty()
                            ? ToolResult.ok("Inventory is empty")
                            : ToolResult.ok("Inventory: " + PerceptionSnapshot.counts(counts, ""));
                }))
                .register(instantTool("stop", "Cancel the current task and go back to the default activity.", (c, a) -> {
                    c.taskRunner().stop("stopped by the LLM");
                    return ToolResult.ok("stopped");
                }));
    }

    private static Task goTo(CompanionEntity c, JsonObject args) {
        if (args.has("target")) {
            Vec3 owner = c.owner().map(p -> p.position()).orElseThrow(() -> new IllegalArgumentException("the owner is not around"));
            return new GoToTask(owner);
        }
        if (args.has("x") && args.has("y") && args.has("z")) {
            return new GoToTask(new Vec3(args.get("x").getAsDouble(), args.get("y").getAsDouble(), args.get("z").getAsDouble()));
        }
        throw new IllegalArgumentException("need x,y,z or target=owner");
    }

    private static java.util.Optional<Item> parseItem(String name) {
        Identifier id = Identifier.tryParse(name);
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(BuiltInRegistries.ITEM.getValue(id));
    }

    @FunctionalInterface
    private interface TaskFactory {
        Task create(CompanionEntity companion, JsonObject args);
    }

    @FunctionalInterface
    private interface Action {
        ToolResult run(CompanionEntity companion, JsonObject args);
    }

    private static Tool<CompanionEntity> taskTool(String name, String description, JsonObject schema, TaskFactory factory) {
        return tool(name, description, schema, (c, args) -> {
            Task task;
            try {
                task = factory.create(c, args);
            } catch (IllegalArgumentException e) {
                return ToolResult.error(e.getMessage());
            }
            int id = c.taskRunner().start(task, name);
            return ToolResult.ok("task #" + id + " started: " + task.describe());
        });
    }

    private static Tool<CompanionEntity> instantTool(String name, String description, Action action) {
        return tool(name, description, SchemaBuilder.object().build(), action);
    }

    private static Tool<CompanionEntity> tool(String name, String description, JsonObject schema, Action action) {
        return new Tool<>() {
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
                return schema;
            }

            @Override
            public ToolResult execute(CompanionEntity ctx, JsonObject args) {
                return action.run(ctx, args);
            }
        };
    }
}
