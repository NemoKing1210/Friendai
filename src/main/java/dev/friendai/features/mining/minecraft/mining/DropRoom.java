package dev.friendai.features.mining.minecraft.mining;

import dev.friendai.task.TaskContext;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** Whether the inventory can take a block's drops, predicted before the break so a full bag leaves the block intact. */
public final class DropRoom {
    private DropRoom() {}

    public static boolean hasRoom(TaskContext c, BlockPos pos) {
        BlockState state = c.level().getBlockState(pos);
        ItemStack tool = c.companion().getMainHandItem();
        for (int i = 0; i < c.companion().inventory().getContainerSize(); i++) {
            ItemStack candidate = c.companion().inventory().getItem(i);
            if (!candidate.isEmpty() && candidate.getDestroySpeed(state) > tool.getDestroySpeed(state)) {
                tool = candidate;
            }
        }
        List<ItemStack> drops =
                Block.getDrops(state, c.level(), pos, c.level().getBlockEntity(pos), c.companion(), tool);
        SimpleContainer projected =
                new SimpleContainer(c.companion().inventory().getContainerSize());
        for (int i = 0; i < projected.getContainerSize(); i++) {
            projected.setItem(i, c.companion().inventory().getItem(i).copy());
        }
        for (ItemStack drop : drops) {
            if (!projected.addItem(drop.copy()).isEmpty()) {
                return false;
            }
        }
        return true;
    }
}
