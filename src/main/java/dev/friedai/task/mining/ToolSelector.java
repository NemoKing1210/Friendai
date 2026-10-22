package dev.friedai.task.mining;

import dev.friedai.entity.CompanionEntity;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

public final class ToolSelector {
    private ToolSelector() {}

    /** Puts the inventory stack that breaks {@code state} fastest into the main hand. */
    public static void equipBest(CompanionEntity companion, BlockState state) {
        SimpleContainer inventory = companion.inventory();
        float bestSpeed = companion.getMainHandItem().getDestroySpeed(state);
        int bestSlot = -1;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            float speed = stack.getDestroySpeed(state);
            if (speed > bestSpeed) {
                bestSpeed = speed;
                bestSlot = i;
            }
        }
        if (bestSlot < 0) {
            return;
        }
        ItemStack best = inventory.removeItemNoUpdate(bestSlot);
        ItemStack previous = companion.getMainHandItem();
        companion.setItemSlot(EquipmentSlot.MAINHAND, best);
        if (!previous.isEmpty()) {
            ItemStack leftover = inventory.addItem(previous);
            if (!leftover.isEmpty()) {
                companion.spawnAtLocation((net.minecraft.server.level.ServerLevel) companion.level(), leftover);
            }
        }
    }
}
