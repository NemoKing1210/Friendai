package dev.friendai.menu;

import dev.friendai.entity.CompanionEntity;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** The companion's six equipment slots (armor, main hand, off hand) as a container the menu can show. */
final class CompanionEquipmentContainer implements Container {
    static final EquipmentSlot[] SLOTS = {
        EquipmentSlot.HEAD,
        EquipmentSlot.CHEST,
        EquipmentSlot.LEGS,
        EquipmentSlot.FEET,
        EquipmentSlot.MAINHAND,
        EquipmentSlot.OFFHAND
    };

    private final CompanionEntity companion;

    CompanionEquipmentContainer(CompanionEntity companion) {
        this.companion = companion;
    }

    @Override
    public int getContainerSize() {
        return SLOTS.length;
    }

    @Override
    public boolean isEmpty() {
        for (EquipmentSlot slot : SLOTS) {
            if (!companion.getItemBySlot(slot).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public ItemStack getItem(int slot) {
        return companion.getItemBySlot(SLOTS[slot]);
    }

    @Override
    public ItemStack removeItem(int slot, int count) {
        ItemStack current = getItem(slot);
        if (current.isEmpty() || count <= 0) {
            return ItemStack.EMPTY;
        }
        ItemStack copy = current.copy();
        ItemStack removed = copy.split(count);
        setItem(slot, copy);
        return removed;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        ItemStack current = getItem(slot);
        setItem(slot, ItemStack.EMPTY);
        return current;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        companion.setItemSlot(SLOTS[slot], stack);
    }

    @Override
    public void setChanged() {
        // The entity holds the stacks; there is nothing to cache.
    }

    @Override
    public boolean stillValid(Player player) {
        return companion.isAlive();
    }

    @Override
    public void clearContent() {
        for (int slot = 0; slot < SLOTS.length; slot++) {
            setItem(slot, ItemStack.EMPTY);
        }
    }
}
