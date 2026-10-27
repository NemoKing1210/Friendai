package dev.friendai.task.inventory;

import dev.friendai.task.interact.CompanionHands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;

/** A one-use placement item that is either returned to its owner or discarded when it was synthesized for creative use. */
public final class CreativeItemLease {
    private final ItemStack stack;
    private final boolean synthetic;

    private CreativeItemLease(ItemStack stack, boolean synthetic) {
        if (stack == null || stack.isEmpty()) {
            throw new IllegalArgumentException("a lease needs one item");
        }
        this.stack = stack.copyWithCount(1);
        this.synthetic = synthetic;
    }

    public static CreativeItemLease carried(ItemStack stack) {
        return new CreativeItemLease(stack, false);
    }

    public static CreativeItemLease synthetic(ItemStack stack) {
        return new CreativeItemLease(stack, true);
    }

    public InteractionResult place(CompanionHands hands, BlockPos against, Direction face) {
        return synthetic ? hands.placeTemporary(stack, against, face) : hands.place(stack, against, face);
    }

    public boolean synthetic() {
        return synthetic;
    }
}
