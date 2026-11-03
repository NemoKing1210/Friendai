package dev.friendai.features.combat.minecraft;

import dev.friendai.entity.CompanionEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

/**
 * One temporary equipment swap whose displaced stack keeps living in the inventory slot the incoming stack came from.
 *
 * <p>Nothing is owned by this object except a copy used to recognise the parked stack, so death, save/reload, the menu
 * and a later task all see the item where a player would look for it. A swap is restored by swapping back; when the
 * parked stack is gone (the owner took it, it was consumed) nothing is invented and the hand ends empty.
 */
public final class HandSwap {
    private final int slot;
    private final EquipmentSlot hand;
    private final ItemStack displaced;

    private HandSwap(int slot, EquipmentSlot hand, ItemStack displaced) {
        this.slot = slot;
        this.hand = hand;
        this.displaced = displaced;
    }

    /** Moves the stack in {@code inventorySlot} into {@code hand}; the hand's previous stack waits in that slot. */
    public static HandSwap equip(CompanionEntity companion, int inventorySlot, EquipmentSlot hand) {
        return install(companion, inventorySlot, hand, companion.inventory().removeItemNoUpdate(inventorySlot));
    }

    /** The same swap when the incoming stack was already taken out of the inventory (a food stack being eaten). */
    public static HandSwap install(CompanionEntity companion, int slot, EquipmentSlot hand, ItemStack incoming) {
        ItemStack previous = companion.getItemBySlot(hand).copy();
        companion.setItemSlot(hand, incoming);
        companion.inventory().setItem(slot, previous);
        return new HandSwap(slot, hand, previous);
    }

    /**
     * Puts the hand's current stack into the slot and the parked stack back into the hand. Returns false when the parked
     * stack is no longer in the slot: the current stack then goes back to the inventory (spilling only what does not
     * fit) and the hand is left empty rather than restoring a copy of an item that no longer exists.
     */
    public boolean restore(CompanionEntity companion) {
        ItemStack current = companion.getItemBySlot(hand);
        ItemStack parked = slot >= 0 && slot < companion.inventory().getContainerSize()
                ? companion.inventory().getItem(slot)
                : ItemStack.EMPTY;
        if (!displaced.isEmpty() && ItemStack.isSameItemSameComponents(parked, displaced)) {
            companion.inventory().setItem(slot, current);
            companion.setItemSlot(hand, parked);
            return true;
        }
        companion.setItemSlot(hand, ItemStack.EMPTY);
        if (!current.isEmpty()) {
            ItemStack leftover = companion.inventory().addItem(current);
            if (!leftover.isEmpty() && companion.level() instanceof ServerLevel level) {
                companion.spawnAtLocation(level, leftover);
            }
        }
        return false;
    }

    /** A copy of the stack waiting in the inventory, for diagnostics. */
    public ItemStack displaced() {
        return displaced.copy();
    }

    public EquipmentSlot hand() {
        return hand;
    }
}
