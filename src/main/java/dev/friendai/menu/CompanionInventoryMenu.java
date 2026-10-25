package dev.friendai.menu;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.tool.InventoryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.equipment.Equippable;
import org.jetbrains.annotations.Nullable;

/**
 * The companion's inventory window: six equipment slots, its 27 inventory slots and the viewer's own inventory.
 * The server side wraps the live entity; the client side mirrors the stacks the server sends.
 */
public final class CompanionInventoryMenu extends AbstractContainerMenu {
    public static final int GEAR_SLOTS = CompanionEquipmentContainer.SLOTS.length;
    public static final int COMPANION_SLOTS = 27;
    private static final int COMPANION_START = GEAR_SLOTS;
    private static final int COMPANION_END = GEAR_SLOTS + COMPANION_SLOTS;
    private static final int PLAYER_END = COMPANION_END + 27 + 9;

    private final CompanionEntity companion;
    private final boolean live;

    /** Server side: the slots act on the companion itself. */
    public CompanionInventoryMenu(int containerId, Inventory playerInventory, CompanionEntity companion) {
        this(containerId, playerInventory, companion, true);
    }

    /** Client side: the menu is built from the entity id in the open-screen packet, before any stack is sent. */
    public CompanionInventoryMenu(int containerId, Inventory playerInventory, int entityId) {
        this(
                containerId,
                playerInventory,
                playerInventory.player.level().getEntity(entityId) instanceof CompanionEntity found ? found : null,
                false);
    }

    private CompanionInventoryMenu(
            int containerId, Inventory playerInventory, CompanionEntity companion, boolean live) {
        super(FriendaiMenus.TYPE, containerId);
        this.companion = companion;
        this.live = live;
        Container gear = live ? new CompanionEquipmentContainer(companion) : new SimpleContainer(GEAR_SLOTS);
        Container items = live ? companion.inventory() : new SimpleContainer(COMPANION_SLOTS);
        for (int slot = 0; slot < GEAR_SLOTS; slot++) {
            // Armor in a column on the left like the player inventory; the two hands together under the model.
            int x = slot < 4 ? 8 : 77 + (slot - 4) * 18;
            int y = slot < 4 ? 18 + slot * 18 : 62;
            addSlot(new GearSlot(gear, slot, CompanionEquipmentContainer.SLOTS[slot], x, y));
        }
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(items, row * 9 + col, 8 + col * 18, 104 + row * 18));
            }
        }
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInventory, 9 + row * 9 + col, 8 + col * 18, 174 + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInventory, col, 8 + col * 18, 232));
        }
    }

    /** The companion this menu shows, or {@code null} on the client when the entity is out of tracking range. */
    @Nullable
    public CompanionEntity companion() {
        return companion;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (slot == null || !slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack copy = stack.copy();
        if (index < COMPANION_END) {
            // Equipment and companion inventory go to the player, the hotbar last.
            if (!moveItemStackTo(stack, COMPANION_END, PLAYER_END, true)) {
                return ItemStack.EMPTY;
            }
        } else {
            // A gear item that fits an empty equipment slot goes there; everything else to the companion.
            if (!moveToGear(stack) && !moveItemStackTo(stack, COMPANION_START, COMPANION_END, false)) {
                return ItemStack.EMPTY;
            }
        }
        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        if (stack.getCount() == copy.getCount()) {
            return ItemStack.EMPTY;
        }
        slot.onTake(player, stack);
        return copy;
    }

    /** Only wearable items go to the equipment slots on a shift-click; everything else goes to the inventory. */
    private boolean moveToGear(ItemStack stack) {
        if (stack.get(DataComponents.EQUIPPABLE) == null) {
            return false;
        }
        for (int index = 0; index < GEAR_SLOTS; index++) {
            Slot gear = slots.get(index);
            if (!gear.hasItem() && gear.mayPlace(stack)) {
                return moveItemStackTo(stack, index, index + 1, false);
            }
        }
        return false;
    }

    @Override
    public boolean stillValid(Player player) {
        if (!live) {
            // The client keeps the screen until the server closes it.
            return true;
        }
        return InventoryAccess.check(
                        player.getUUID().equals(companion.ownerUuid()),
                        player.level() == companion.level(),
                        companion.isAlive() && !companion.isRemoved(),
                        player.distanceTo(companion))
                == InventoryAccess.Denial.NONE;
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        if (live && player instanceof net.minecraft.server.level.ServerPlayer) {
            companion.removeInventoryViewer();
        }
    }

    /** A gear slot: armor accepts only what belongs in it, the hands accept anything. One item per slot. */
    private static final class GearSlot extends Slot {
        private final EquipmentSlot gear;

        GearSlot(Container container, int index, EquipmentSlot gear, int x, int y) {
            super(container, index, x, y);
            this.gear = gear;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            if (gear == EquipmentSlot.MAINHAND || gear == EquipmentSlot.OFFHAND) {
                return true;
            }
            Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
            return equippable != null && equippable.slot() == gear;
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }
    }
}
