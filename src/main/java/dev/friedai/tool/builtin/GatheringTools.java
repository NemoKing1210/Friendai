package dev.friedai.tool.builtin;

import dev.friedai.entity.CompanionEntity;
import dev.friedai.task.builtin.MineBlocksTask;
import dev.friedai.task.builtin.PickupItemsTask;
import dev.friedai.task.mining.BlockMatcher;
import dev.friedai.tool.SchemaBuilder;
import dev.friedai.tool.ToolRegistry;
import java.util.List;
import java.util.function.IntSupplier;

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
        ToolKit.add(registry, defs, new TaskToolDef("pickup_items", "Pick up dropped items within the given radius.",
                SchemaBuilder.object().integer("radius", "Search radius in blocks", 1, 16).required("radius").build(),
                (c, a) -> new PickupItemsTask(a.get("radius").getAsInt()), true));
    }
}
