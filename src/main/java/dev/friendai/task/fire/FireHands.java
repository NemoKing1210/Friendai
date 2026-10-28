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
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;

/**
 * The bucket part of putting a fire out. Every placement goes through {@link dev.friendai.task.interact.CompanionHands},
 * so protection and {@code level.mayInteract} still apply; a failed attempt leaves the item with the companion,
 * because the fake player's leftovers are returned.
 *
 * <p>A bucket is used the way a player uses one: right-clicking in the air ({@code useItem}), because the bucket item
 * places its fluid from its own raycast. Water is aimed at the face of a solid neighbour so it lands in the fire's
 * cell; a source is taken back by aiming straight at it.
 *
 * <p>This is the seam TODO item 26.4 names: when the general "use one item the way a player does" path lands in
 * {@code CompanionHands}, this class calls it instead of aiming a bucket itself.
 */
public final class FireHands {
    private static final ItemMatcher WATER_BUCKET =
            ItemMatcher.parse("minecraft:water_bucket").orElseThrow();
    private static final ItemMatcher EMPTY_BUCKET =
            ItemMatcher.parse("minecraft:bucket").orElseThrow();
    private static final ItemMatcher POWDER_SNOW =
            ItemMatcher.parse("minecraft:powder_snow_bucket").orElseThrow();

    private final CompanionEntity companion;
    private final ServerLevel level;

    public FireHands(TaskContext c) {
        this.companion = c.companion();
        this.level = c.level();
    }

    public boolean hasWater() {
        return InventoryOps.count(companion.inventory(), WATER_BUCKET) > 0;
    }

    /** Whether an empty bucket is carried, so a source can be taken back. */
    public boolean hasEmptyBucket() {
        return InventoryOps.count(companion.inventory(), EMPTY_BUCKET) > 0;
    }

    /**
     * Pours one water bucket at {@code cell} and returns the cell the source actually landed in, or null when
     * nothing was poured. The bucket's own raycast decides that: pouring at a fire block puts the source on top of
     * it and the water runs down into the fire, so the source can be the cell above. Only a source this method
     * made is ever reported — a pond next to the fire is not ours to take.
     */
    public BlockPos placeWater(BlockPos cell) {
        return pour(cell);
    }

    /**
     * Pours water into a lit campfire, which flips its lit state; true when it is out. A waterlogged campfire holds
     * its water inside the block, so there is no source to take back afterwards.
     */
    public boolean douseCampfire(BlockPos cell) {
        pour(cell);
        return !lit(cell);
    }

    /**
     * Takes the source back with an empty bucket; true when no source is left around {@code cell}. The bucket is
     * aimed at, and the item's own raycast picks, a source; the cell where the water ended up is not always the
     * cell that was clicked (water poured on top of a fire block runs down into it), so the source is looked for
     * next to the cell as well.
     */
    public boolean recoverBucket(BlockPos cell) {
        BlockPos source = sourceNear(cell);
        if (source == null) {
            return true;
        }
        return recoverSource(source) == null;
    }

    /** Takes the source at exactly {@code source} back; returns the cell that still holds water, or null. */
    public BlockPos recoverSource(BlockPos source) {
        List<ItemStack> taken = InventoryOps.remove(companion.inventory(), EMPTY_BUCKET, 1);
        if (taken.isEmpty()) {
            return source;
        }
        companion.hands().useItem(taken.getFirst(), Vec3.atCenterOf(source));
        return source(source) ? source : null;
    }

    /**
     * Places a powder snow block at {@code cell}; the companion then stands in it to put its own fire out. Powder
     * snow is a block item, so the ordinary placement path works.
     */
    public boolean placePowderSnow(BlockPos cell) {
        for (Direction direction : clickOrder()) {
            BlockPos against = cell.relative(direction);
            if (!clickable(against)) {
                continue;
            }
            List<ItemStack> taken = InventoryOps.remove(companion.inventory(), POWDER_SNOW, 1);
            if (taken.isEmpty()) {
                return false;
            }
            companion.hands().place(taken.getFirst(), against, direction.getOpposite());
            return level.getBlockState(cell).is(net.minecraft.world.level.block.Blocks.POWDER_SNOW);
        }
        return false;
    }

    /** Pours one bucket and reports where its source landed, or null when nothing was poured. */
    private BlockPos pour(BlockPos cell) {
        for (Direction direction : clickOrder()) {
            BlockPos against = cell.relative(direction);
            if (!clickable(against)) {
                continue;
            }
            List<ItemStack> taken = InventoryOps.remove(companion.inventory(), WATER_BUCKET, 1);
            if (taken.isEmpty()) {
                return null;
            }
            Vec3 aim = Vec3.atCenterOf(against).relative(direction.getOpposite(), 0.5);
            companion.hands().useItem(taken.getFirst(), aim);
            return sourceNear(cell);
        }
        return null;
    }

    /** Faces to click, in the order that most often leaves the placed water where it is wanted. */
    private static Direction[] clickOrder() {
        return new Direction[] {
            Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, Direction.UP
        };
    }

    private boolean clickable(BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return !state.isAir() && state.getFluidState().isEmpty();
    }

    /** A pool of water at the cell; water held inside a waterlogged block is not a source to take back. */
    private boolean wet(BlockPos cell) {
        BlockState state = level.getBlockState(cell);
        if (state.getBlock() instanceof SimpleWaterloggedBlock) {
            return false;
        }
        return level.getFluidState(cell).getType().isSame(Fluids.WATER);
    }

    /**
     * The source this pour can have made: the cell itself, or the cell above it (water poured on the top face of a
     * fire block lands there and runs down). Nothing further away is ours to take back.
     */
    private BlockPos sourceNear(BlockPos cell) {
        if (source(cell)) {
            return cell;
        }
        BlockPos above = cell.above();
        return source(above) ? above : null;
    }

    /** Whether exactly this cell holds water that can be taken back. */
    public boolean holdsSource(BlockPos pos) {
        return source(pos);
    }

    private boolean source(BlockPos pos) {
        if (level.getBlockState(pos).getBlock() instanceof SimpleWaterloggedBlock) {
            return false;
        }
        return level.getFluidState(pos).getType().isSame(Fluids.WATER)
                && level.getFluidState(pos).isSource();
    }

    private boolean lit(BlockPos cell) {
        BlockState state = level.getBlockState(cell);
        return state.is(BlockTags.CAMPFIRES) && state.getValue(CampfireBlock.LIT);
    }
}
