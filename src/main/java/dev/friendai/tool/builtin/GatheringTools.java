package dev.friendai.tool.builtin;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.task.builtin.MineBlockAtTask;
import dev.friendai.task.builtin.MineBlocksTask;
import dev.friendai.task.builtin.PickupItemsTask;
import dev.friendai.task.mining.BlockMatcher;
import dev.friendai.tool.SchemaBuilder;
import dev.friendai.tool.ToolRegistry;
import java.util.List;
import java.util.function.IntSupplier;
import net.minecraft.core.BlockPos;

/** Getting resources: mining blocks and picking up what is lying around. */
final class GatheringTools {
    private GatheringTools() {}

    static void register(ToolRegistry<CompanionEntity> registry, List<TaskToolDef> defs, IntSupplier mineRadius) {
        ToolKit.add(registry, defs, new TaskToolDef("mine_blocks", "Mine N blocks of the given block id or #tag near the companion and collect the drops.",
                SchemaBuilder.object()
                        .string("block", "Block id like minecraft:oak_log, or a tag like #minecraft:logs")
                        .integer("count", "How many blocks to mine", 1, 64)
                        .required("block", "count")
                        .build(),
                (c, a) -> {
                    BlockMatcher matcher = ToolKit.parseBlockMatcher(a.get("block").getAsString());
                    return new MineBlocksTask(matcher, a.get("count").getAsInt(), mineRadius.getAsInt());
                }, true));
        ToolKit.add(registry, defs, new TaskToolDef("mine_block_at", "Mine the block at the given coordinates and collect the drops.",
                SchemaBuilder.object()
                        .integer("x", "Block X coordinate")
                        .integer("y", "Block Y coordinate")
                        .integer("z", "Block Z coordinate")
                        .required("x", "y", "z")
                        .build(),
                (c, a) -> {
                    int x = a.get("x").getAsInt();
                    int y = a.get("y").getAsInt();
                    int z = a.get("z").getAsInt();
                    if (y < c.level().getMinY() || y > c.level().getMaxY()) {
                        throw new IllegalArgumentException("y is outside the world (" + c.level().getMinY() + ".." + c.level().getMaxY() + ")");
                    }
                    if (!c.level().hasChunkAt(x, z)) {
                        throw new IllegalArgumentException("the chunk at " + x + " " + z + " is not loaded");
                    }
                    return new MineBlockAtTask(new BlockPos(x, y, z));
                }, true));
        ToolKit.add(registry, defs, new TaskToolDef("pickup_items", "Pick up dropped items within the given radius.",
                SchemaBuilder.object()
                        .integer("radius", "Search radius in blocks", 1, 16)
                        .string("item", "Item id like minecraft:egg, or a tag; only these are picked up")
                        .required("radius")
                        .build(),
                (c, a) -> new PickupItemsTask(
                        a.get("radius").getAsInt(),
                        a.has("item") && !a.get("item").isJsonNull()
                                ? ToolKit.parseItemMatcher(a.get("item").getAsString())
                                : null),
                true));
    }
}
