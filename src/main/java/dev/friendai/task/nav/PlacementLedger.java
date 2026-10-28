package dev.friendai.task.nav;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

/**
 * The blocks a route placed while it walked, so the task that asked for the route can take them down again. Only the
 * block that was actually placed is remembered; a position whose block has since changed is left alone.
 */
public final class PlacementLedger {
    private final Map<BlockPos, Block> placed = new LinkedHashMap<>();

    /** Records one block placed at {@code pos}; the stack must be the one consumed by the placement. */
    public void record(BlockPos pos, ItemStack stack) {
        if (!(stack.getItem() instanceof BlockItem item)) {
            return;
        }
        placed.put(pos.immutable(), item.getBlock());
    }

    /** Every remembered position, in placement order. */
    public List<BlockPos> positions() {
        return List.copyOf(placed.keySet());
    }

    /** True while the position still holds the block this ledger placed there. */
    public boolean stillPlaced(ServerLevel level, BlockPos pos) {
        Block expected = placed.get(pos);
        return expected != null
                && level.hasChunkAt(pos)
                && level.getBlockState(pos).is(expected);
    }

    public boolean isEmpty() {
        return placed.isEmpty();
    }

    public int size() {
        return placed.size();
    }

    public void clear() {
        placed.clear();
    }
}
