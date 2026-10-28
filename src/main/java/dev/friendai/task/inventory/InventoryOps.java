package dev.friendai.task.inventory;

import dev.friendai.entity.CompanionEntity;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

/** Inventory helpers shared by the inventory skills. */
public final class InventoryOps {
    private InventoryOps() {}

    /** How many items matching the matcher the container holds, across all slots. */
    public static int count(Container inv, ItemMatcher matcher) {
        int total = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (matcher.test(stack)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /**
     * Removes up to {@code max} matching items, walking slots in order. The removed items come back merged per
     * item type, each stack at most its max stack size, ready to be dropped or moved somewhere else.
     */
    public static List<ItemStack> remove(Container inv, ItemMatcher matcher, int max) {
        List<ItemStack> removed = new ArrayList<>();
        int remaining = max;
        for (int i = 0; i < inv.getContainerSize() && remaining > 0; i++) {
            ItemStack slot = inv.getItem(i);
            if (!matcher.test(slot)) {
                continue;
            }
            int take = Math.min(remaining, slot.getCount());
            ItemStack taken = slot.copyWithCount(take);
            slot.shrink(take);
            if (slot.isEmpty()) {
                inv.setItem(i, ItemStack.EMPTY);
            }
            merge(removed, taken);
            remaining -= take;
        }
        inv.setChanged();
        return removed;
    }

    /** Adds the stack to the inventory, dropping whatever does not fit at the companion's feet. */
    public static void insertOrDrop(CompanionEntity c, ItemStack stack) {
        ItemStack leftover = c.inventory().addItem(stack);
        if (leftover.isEmpty()) {
            return;
        }
        c.level().addFreshEntity(new ItemEntity(c.level(), c.getX(), c.getY(), c.getZ(), leftover));
    }

    private static void merge(List<ItemStack> stacks, ItemStack stack) {
        for (ItemStack existing : stacks) {
            if (!ItemStack.isSameItemSameComponents(existing, stack)
                    || existing.getCount() >= existing.getMaxStackSize()) {
                continue;
            }
            int move = Math.min(existing.getMaxStackSize() - existing.getCount(), stack.getCount());
            existing.grow(move);
            stack.shrink(move);
            if (stack.isEmpty()) {
                return;
            }
        }
        if (!stack.isEmpty()) {
            stacks.add(stack);
        }
    }
}
