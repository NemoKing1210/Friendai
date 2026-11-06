package dev.friendai.world.movement;

import dev.friendai.execution.queue.PlanMaterials;
import java.util.LinkedHashSet;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * What a block a plan names actually yields: {@code mine_blocks minecraft:stone} produces cobblestone, and that is the
 * cobblestone a stone tool consumes, so a reserve that named only stone would protect nothing.
 *
 * <p>Drops are asked of the loot table the way {@code DropRoom} does, with an empty tool; a block without one falls
 * back to its own item. Both the block id and every drop id are reserved, so a wrong guess can never leave a stack
 * unprotected.
 */
public final class PlanMaterialSource implements PlanMaterials.ToItemIds {
    private final ServerLevel level;

    private PlanMaterialSource(ServerLevel level) {
        this.level = level;
    }

    public static PlanMaterialSource forLevel(ServerLevel level) {
        return new PlanMaterialSource(level);
    }

    @Override
    public List<String> itemsFor(String blockId) {
        Identifier id = Identifier.tryParse(blockId == null ? "" : blockId);
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) {
            return blockId == null || blockId.isBlank() ? List.of() : List.of(blockId);
        }
        BlockState state = BuiltInRegistries.BLOCK.getValue(id).defaultBlockState();
        List<ItemStack> drops;
        try {
            drops = Block.getDrops(state, level, BlockPos.ZERO, null, null, ItemStack.EMPTY);
        } catch (RuntimeException e) {
            drops = List.of();
        }
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        ids.add(id.toString());
        for (ItemStack stack : drops) {
            if (!stack.isEmpty()) {
                ids.add(idOf(stack.getItem()));
            }
        }
        if (drops.isEmpty()) {
            Item item = state.getBlock().asItem();
            if (item != Items.AIR) {
                ids.add(idOf(item));
            }
        }
        return List.copyOf(ids);
    }

    private static String idOf(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }
}
