package dev.friendai.task.mining;

import dev.friendai.entity.CompanionEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Picks what the companion holds while it mines.
 *
 * <p>An item is only worth holding when it breaks the block faster than an empty hand. A wrong tool is no faster
 * than a hand, so it is put away instead of being swung at the block: a pickaxe left in the hand after mining stone
 * goes back to the inventory before a tree is felled, and the companion mines bare-handed when it has no axe. This
 * picks the right kind of tool; whether a tier is good enough for the block's drops is not part of the choice.
 */
public final class ToolSelector {
    /** Vanilla speed of an empty hand, and of any item with no speed rule for the block. */
    static final float BARE_HAND_SPEED = 1.0F;

    private ToolSelector() {}

    /**
     * Puts the best stack for {@code state} into the main hand, or empties the hand when no stack beats a bare hand.
     * The previous item goes back to the inventory; when the inventory is full it is dropped.
     */
    public static void equipBest(CompanionEntity companion, BlockState state) {
        SimpleContainer inventory = companion.inventory();
        ItemStack current = companion.getMainHandItem();
        float bestSpeed = speedOf(current, state);
        int bestSlot = -1;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            float speed = speedOf(stack, state);
            if (speed > bestSpeed) {
                bestSpeed = speed;
                bestSlot = i;
            }
        }
        if (bestSlot < 0) {
            if (bestSpeed > BARE_HAND_SPEED) {
                return;
            }
            // Nothing helps: a wrong tool looks (and is) wrong in the hand, so hold nothing instead.
            putAway(companion, current);
            companion.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            return;
        }
        ItemStack best = inventory.removeItemNoUpdate(bestSlot);
        putAway(companion, current);
        companion.setItemSlot(EquipmentSlot.MAINHAND, best);
    }

    /**
     * How fast {@code stack} would break {@code state}; the bare-hand speed when the item has no working rule for
     * this block (a pickaxe against wood, a shovel against stone).
     */
    static float speedOf(ItemStack stack, BlockState state) {
        if (stack.isEmpty()) {
            return BARE_HAND_SPEED;
        }
        return Math.max(BARE_HAND_SPEED, stack.getDestroySpeed(state));
    }

    private static void putAway(CompanionEntity companion, ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        ItemStack leftover = companion.inventory().addItem(stack);
        if (!leftover.isEmpty() && companion.level() instanceof ServerLevel level) {
            companion.spawnAtLocation(level, leftover);
        }
    }
}
