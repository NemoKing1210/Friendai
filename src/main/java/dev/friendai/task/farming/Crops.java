package dev.friendai.task.farming;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.AttachedStemBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.SugarCaneBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;

/** What can be harvested, and which item replants it. */
public final class Crops {
    private Crops() {}

    /** The ripe block at pos, or empty if there is nothing to harvest there. */
    public static Optional<Harvestable> harvestable(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof CropBlock crop && crop.isMaxAge(state)) {
            return Optional.of(new Harvestable(
                    pos.immutable(), Harvestable.Kind.CROP, seedFor(state).orElse(null)));
        }
        if (state.getBlock() instanceof NetherWartBlock && state.getValue(NetherWartBlock.AGE) >= 3) {
            return Optional.of(new Harvestable(pos.immutable(), Harvestable.Kind.NETHER_WART, Items.NETHER_WART));
        }
        if ((state.is(Blocks.MELON) || state.is(Blocks.PUMPKIN)) && hasAttachedStem(level, pos)) {
            return Optional.of(new Harvestable(pos.immutable(), Harvestable.Kind.FRUIT, null));
        }
        if (state.getBlock() instanceof SugarCaneBlock
                && level.getBlockState(pos.below()).getBlock() instanceof SugarCaneBlock) {
            return Optional.of(new Harvestable(pos.immutable(), Harvestable.Kind.CANE, null));
        }
        if (state.getBlock() instanceof SweetBerryBushBlock && state.getValue(SweetBerryBushBlock.AGE) >= 2) {
            return Optional.of(new Harvestable(pos.immutable(), Harvestable.Kind.BERRIES, null));
        }
        return Optional.empty();
    }

    /** The item that plants this crop again. */
    public static Optional<Item> seedFor(BlockState state) {
        if (state.getBlock() instanceof CropBlock) {
            return Optional.of(state.getBlock().asItem());
        }
        if (state.getBlock() instanceof NetherWartBlock) {
            return Optional.of(Items.NETHER_WART);
        }
        if (state.getBlock() instanceof SweetBerryBushBlock) {
            return Optional.of(Items.SWEET_BERRIES);
        }
        if (state.getBlock() instanceof SugarCaneBlock) {
            return Optional.of(Items.SUGAR_CANE);
        }
        return Optional.empty();
    }

    /** Whether the item can be planted on farmland. */
    public static boolean isPlantableSeed(Item item) {
        if (item == Items.WHEAT_SEEDS
                || item == Items.CARROT
                || item == Items.POTATO
                || item == Items.BEETROOT_SEEDS
                || item == Items.NETHER_WART) {
            return true;
        }
        return item instanceof BlockItem blockItem && blockItem.getBlock() instanceof CropBlock;
    }

    /** Whether a seed can be planted on the block below. */
    public static boolean isReplantSurface(BlockState below, Item seed) {
        return seed == Items.NETHER_WART ? below.is(Blocks.SOUL_SAND) : below.is(Blocks.FARMLAND);
    }

    /** Whether the block is a crop the companion planted, for reporting. */
    public static boolean isCrop(BlockState state) {
        return state.getBlock() instanceof CropBlock || state.getBlock() instanceof NetherWartBlock;
    }

    private static boolean hasAttachedStem(ServerLevel level, BlockPos pos) {
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos stemPos = pos.relative(direction);
            BlockState stem = level.getBlockState(stemPos);
            if (stem.getBlock() instanceof AttachedStemBlock
                    && stem.getValue(AttachedStemBlock.FACING) == direction.getOpposite()) {
                return true;
            }
        }
        return false;
    }
}
