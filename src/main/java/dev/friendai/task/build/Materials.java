package dev.friendai.task.build;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.task.interact.ContainerAccess;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/** Counts the items a blueprint needs and the items the companion can reach: its own inventory and nearby storage. */
public final class Materials {
    public static final int STORAGE_RADIUS = 16;

    private Materials() {}

    /**
     * The items needed to build: one per placed block, plus the foundation. Double blocks count once (the upper half
     * appears when the lower one is placed), and air specs need nothing.
     */
    public static Map<Item, Integer> needed(
            List<Placement.PlacedSpec> specs, List<Placement.PlacedSpec> foundation) {
        Map<Item, Integer> needed = new LinkedHashMap<>();
        for (Placement.PlacedSpec spec : specs) {
            add(needed, spec.wanted());
        }
        for (Placement.PlacedSpec spec : foundation) {
            add(needed, spec.wanted());
        }
        return needed;
    }

    private static void add(Map<Item, Integer> needed, BlockState state) {
        if (needsNoItem(state)) {
            return;
        }
        needed.merge(itemFor(state), 1, Integer::sum);
    }

    /** Air is placed by nobody; the upper half of a double block appears when the lower one is placed. */
    static boolean needsNoItem(BlockState state) {
        if (state.isAir()) {
            return true;
        }
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) {
            return true;
        }
        return state.hasProperty(BlockStateProperties.BED_PART)
                && state.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD;
    }

    /** The item that places this state; wall variants map to their free-standing item. */
    public static Item itemFor(BlockState state) {
        Item item = state.getBlock().asItem();
        if (item != Items.AIR) {
            return item;
        }
        String path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        return switch (path) {
            case "wall_torch" -> Items.TORCH;
            case "redstone_wire" -> Items.REDSTONE;
            default -> throw new IllegalArgumentException(
                    BuiltInRegistries.BLOCK.getKey(state.getBlock()) + " can't be placed by hand");
        };
    }

    /** What the companion has at hand: its inventory, its main hand, and storage within {@link #STORAGE_RADIUS}. */
    public static Map<Item, Integer> have(CompanionEntity companion, ServerLevel level) {
        Map<Item, Integer> have = carried(companion);
        return have;
    }

    /** What the companion carries in its own inventory and main hand, without any storage. */
    public static Map<Item, Integer> carried(CompanionEntity companion) {
        Map<Item, Integer> have = new LinkedHashMap<>();
        for (int slot = 0; slot < companion.inventory().getContainerSize(); slot++) {
            count(have, companion.inventory().getItem(slot));
        }
        count(have, companion.getMainHandItem());
        return have;
    }

    /** A double chest's second half, so its contents are not counted twice. */
    private static boolean isSecondHalf(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof ChestBlock)) {
            return false;
        }
        BlockPos other = ChestBlock.getConnectedBlockPos(pos, state);
        return !other.equals(pos) && level.getBlockState(other).getBlock() instanceof ChestBlock && other.compareTo(pos) < 0;
    }

    private static void count(Map<Item, Integer> have, ItemStack stack) {
        if (!stack.isEmpty()) {
            have.merge(stack.getItem(), stack.getCount(), Integer::sum);
        }
    }
}
