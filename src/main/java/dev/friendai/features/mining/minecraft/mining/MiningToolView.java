package dev.friendai.features.mining.minecraft.mining;

import dev.friendai.entity.CompanionEntity;
import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/** Finds the first carried tool that satisfies Minecraft's harvest rules for a block. */
public final class MiningToolView {
    private MiningToolView() {}

    /** The short id of a carried tool that harvests {@code state}, if the companion has one. */
    public static Optional<String> currentToolFor(CompanionEntity companion, BlockState state) {
        Optional<String> inHand = toolName(companion.getMainHandItem(), state);
        if (inHand.isPresent()) {
            return inHand;
        }
        Optional<String> offHand = toolName(companion.getOffhandItem(), state);
        if (offHand.isPresent()) {
            return offHand;
        }
        for (int i = 0; i < companion.inventory().getContainerSize(); i++) {
            Optional<String> tool = toolName(companion.inventory().getItem(i), state);
            if (tool.isPresent()) {
                return tool;
            }
        }
        return Optional.empty();
    }

    private static Optional<String> toolName(ItemStack stack, BlockState state) {
        if (stack.isEmpty() || !stack.isCorrectToolForDrops(state)) {
            return Optional.empty();
        }
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return Optional.of(id.getNamespace().equals("minecraft") ? id.getPath() : id.toString());
    }
}
