package dev.friendai.task.fire;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.task.TaskContext;
import dev.friendai.task.inventory.InventoryOps;
import dev.friendai.task.inventory.ItemMatcher;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;

/**
 * The bucket part of putting a fire out. Every placement goes through {@link CompanionHands}, so protection and
 * {@code level.mayInteract} still apply; a failed attempt leaves the item with the companion because the fake
 * player's leftovers are returned.
 *
 * <p>This is the seam TODO item 26.4 names: when the general "use one item the way a player does" path lands in
 * {@link CompanionHands}, this class calls it instead of aiming a bucket itself.
 */
public final class FireHands {
    private static final ItemMatcher WATER_BUCKET = ItemMatcher.parse("minecraft:water_bucket").orElseThrow();
    private static final ItemMatcher EMPTY_BUCKET = ItemMatcher.parse("minecraft:bucket").orElseThrow();

    private final CompanionEntity companion;
    private final ServerLevel level;

    public FireHands(TaskContext c) {
        this.companion = c.companion();
        this.level = c.level();
    }

    public boolean hasWater() {
        return InventoryOps.count(companion.inventory(), WATER_BUCKET) > 0;
    }

    /** Pours one water bucket into {@code cell}; true when water is there afterwards. */
    public boolean placeWater(BlockPos cell) {
        return pour(cell) && wet(cell);
    }

    /** Pours water into a lit campfire, which flips its lit state; true when it is out. */
    public boolean douseCampfire(BlockPos cell) {
        return pour(cell) && !lit(cell);
    }

    /** Takes the source back with an empty bucket; true when the cell is no longer water. */
    public boolean recoverBucket(BlockPos cell) {
        BlockState state = level.getBlockState(cell);
        if (!wet(cell) && !state.is(BlockTags.CAMPFIRES)) {
            return true;
        }
        List<ItemStack> taken = InventoryOps.remove(companion.inventory(), EMPTY_BUCKET, 1);
        if (taken.isEmpty()) {
            return false;
        }
        companion.hands().place(taken.getFirst(), cell, Direction.UP);
        return !wet(cell);
    }

    /**
     * Places a powder snow block at {@code cell} and reports whether it is there. The companion stands in it to
     * put its own fire out.
     */
    public boolean placePowderSnow(BlockPos cell) {
        ItemMatcher snow = ItemMatcher.parse("minecraft:powder_snow_bucket").orElseThrow();
        for (Direction direction : clickOrder(cell)) {
            BlockPos against = cell.relative(direction);
            if (!clickable(against)) {
                continue;
            }
            List<ItemStack> taken = InventoryOps.remove(companion.inventory(), snow, 1);
            if (taken.isEmpty()) {
                return false;
            }
            companion.hands().place(taken.getFirst(), against, direction.getOpposite());
            return level.getBlockState(cell).is(net.minecraft.world.level.block.Blocks.POWDER_SNOW);
        }
        return false;
    }

    private boolean pour(BlockPos cell) {
        for (Direction direction : clickOrder(cell)) {
            BlockPos against = cell.relative(direction);
            if (!clickable(against)) {
                continue;
            }
            List<ItemStack> taken = InventoryOps.remove(companion.inventory(), WATER_BUCKET, 1);
            if (taken.isEmpty()) {
                return false;
            }
            companion.hands().place(taken.getFirst(), against, direction.getOpposite());
            return true;
        }
        return false;
    }

    /** Faces to click, in the order that most often leaves the placed water where it is wanted. */
    private static Direction[] clickOrder(BlockPos cell) {
        return new Direction[] {Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST,
                Direction.EAST, Direction.UP};
    }

    private boolean clickable(BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return !state.isAir() && state.getFluidState().isEmpty();
    }

    private boolean wet(BlockPos cell) {
        return level.getFluidState(cell).getType().isSame(Fluids.WATER);
    }

    private boolean lit(BlockPos cell) {
        BlockState state = level.getBlockState(cell);
        return state.is(BlockTags.CAMPFIRES) && state.getValue(CampfireBlock.LIT);
    }
}
