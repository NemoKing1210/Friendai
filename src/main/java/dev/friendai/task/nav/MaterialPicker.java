package dev.friendai.task.nav;

import dev.friendai.pathing.BlockList;
import dev.friendai.pathing.MaterialPrices;
import dev.friendai.pathing.PathingConfig;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Chooses the blocks the companion uses to bridge and pillar: full, solid, non-falling blocks it is allowed to
 * place, cheapest material first so it spends dirt before planks.
 */
public final class MaterialPicker {

    private final PathingConfig config;
    private final BlockList breakList;
    private final BlockList placeList;

    public MaterialPicker(PathingConfig config) {
        this(config, breakList(config), placeList(config));
    }

    public MaterialPicker(PathingConfig config, BlockList breakList, BlockList placeList) {
        this.config = config;
        this.breakList = breakList;
        this.placeList = placeList;
    }

    /** How many blocks the inventory holds that may be used as material. */
    public int count(SimpleContainer inventory) {
        int total = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && qualifies(stack)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /** The material price of the cheapest usable block; 1 when nothing qualifies. */
    public double cheapestPrice(SimpleContainer inventory) {
        double best = 1;
        boolean found = false;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty() || !qualifies(stack)) {
                continue;
            }
            double price = price(stack);
            if (!found || price < best) {
                best = price;
                found = true;
            }
        }
        return best;
    }

    /** Removes one of the cheapest usable blocks from the inventory and returns it. */
    public Optional<ItemStack> takeCheapest(SimpleContainer inventory) {
        int slot = -1;
        double best = Double.MAX_VALUE;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty() || !qualifies(stack)) {
                continue;
            }
            double price = price(stack);
            if (price < best) {
                best = price;
                slot = i;
            }
        }
        if (slot < 0) {
            return Optional.empty();
        }
        ItemStack stack = inventory.getItem(slot);
        ItemStack taken = stack.copyWithCount(1);
        stack.shrink(1);
        if (stack.isEmpty()) {
            inventory.setItem(slot, ItemStack.EMPTY);
        } else {
            inventory.setItem(slot, stack);
        }
        return Optional.of(taken);
    }

    /** Whether one item of {@code stack} is a block worth placing on a path. */
    public boolean qualifies(ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem item)) {
            return false;
        }
        Block block = item.getBlock();
        if (block instanceof FallingBlock) {
            return false;
        }
        BlockState state = block.defaultBlockState();
        if (state.hasBlockEntity()) {
            return false;
        }
        if (!Block.isShapeFullBlock(state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO))) {
            return false;
        }
        if (!config.allowPlace()) {
            return false;
        }
        Set<String> tags = LevelCellView.tagsOf(state);
        String id = LevelCellView.idOf(block);
        return !placeList.matches(id, tags) && !breakList.matches(id, tags);
    }

    private static BlockList breakList(PathingConfig config) {
        return BlockList.parse(config.breakBlacklist(), ignored -> {});
    }

    private static BlockList placeList(PathingConfig config) {
        java.util.ArrayList<String> entries = new java.util.ArrayList<>(config.breakBlacklist());
        entries.addAll(config.placeBlacklist());
        return BlockList.parse(entries, ignored -> {});
    }

    private static double price(ItemStack stack) {
        Block block = ((BlockItem) stack.getItem()).getBlock();
        BlockState state = block.defaultBlockState();
        return MaterialPrices.price(LevelCellView.idOf(block), LevelCellView.tagsOf(state));
    }
}
