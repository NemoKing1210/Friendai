package dev.friendai.task.farming;

import dev.friendai.farming.CropBook;
import dev.friendai.farming.CropInfo;
import dev.friendai.farming.Soil;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Builds the planner's {@link CropBook} from the registries, so modded crops are planned like vanilla ones. */
public final class CropIndex {
    private CropIndex() {}

    /** Every {@link CropBlock} with a seed item, plus nether wart; cheap enough to rebuild on each call. */
    public static CropBook of(ServerLevel level) {
        List<CropInfo> infos = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            if (!(block instanceof CropBlock crop)) {
                continue;
            }
            Item seed = crop.asItem();
            if (seed == Items.AIR || !seen.add(id(seed))) {
                continue;
            }
            BlockState ripe = crop.getStateForAge(crop.getMaxAge());
            Item produce = produce(level, ripe, seed);
            infos.add(new CropInfo(
                    blockId(block),
                    id(seed),
                    id(produce),
                    crop.getMaxAge(),
                    Soil.FARMLAND,
                    foodValue(produce),
                    1.0,
                    1.0));
        }
        if (seen.add(id(Items.NETHER_WART))) {
            infos.add(new CropInfo(
                    blockId(Blocks.NETHER_WART),
                    id(Items.NETHER_WART),
                    id(Items.NETHER_WART),
                    3,
                    Soil.SOUL_SAND,
                    0,
                    1.0,
                    1.0));
        }
        return CropBook.of(infos);
    }

    /** The first drop that is not the seed; the produce only feeds the food value and the plan text. */
    private static Item produce(ServerLevel level, BlockState ripe, Item seed) {
        for (ItemStack stack : Block.getDrops(ripe, level, BlockPos.ZERO, null, null, ItemStack.EMPTY)) {
            if (!stack.isEmpty() && stack.getItem() != seed) {
                return stack.getItem();
            }
        }
        return seed;
    }

    private static int foodValue(Item item) {
        FoodProperties food = new ItemStack(item).get(DataComponents.FOOD);
        return food == null ? 0 : food.nutrition();
    }

    static String id(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    static String blockId(Block block) {
        return BuiltInRegistries.BLOCK.getKey(block).toString();
    }
}
