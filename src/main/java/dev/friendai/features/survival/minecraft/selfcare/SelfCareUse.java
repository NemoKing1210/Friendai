package dev.friendai.features.survival.minecraft.selfcare;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.task.TaskExecutionPort;
import dev.friendai.task.inventory.InventoryOps;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;

/**
 * Drinks or eats exactly one item, the way vanilla does it: the stack goes into the main hand, the entity's own use
 * loop runs for the item's use duration, and {@code Consumable} applies the effects and decrements the stack. The
 * previous main-hand item is restored afterwards and anything left is put back, so a task that was holding a pickaxe
 * gets it back and no item can be duplicated.
 *
 * <p>While the use runs the runner is held, so the task cannot re-equip its tool mid-drink (which vanilla would treat
 * as letting go of the item).
 */
final class SelfCareUse {
    /** Far past any consumable's use duration; past it the use is ended and the item returned. */
    private static final int MAX_USE_TICKS = 200;

    /** True while a use owns the hands. A flag, not the stack: consuming the item empties the stack object itself. */
    private boolean active;

    private ItemStack item = ItemStack.EMPTY;
    private ItemStack previous = ItemStack.EMPTY;
    private FoodProperties food;
    private int originalCount;
    private TaskExecutionPort.Hold hold;
    private int ticks;

    /** True while an item is in use, so another consumer must keep its hands off. */
    boolean busy() {
        return active;
    }

    /**
     * Starts consuming {@code stack}; false when a use is already running or the item refused to be used (nothing is
     * lost: the stack is handed back).
     */
    boolean start(CompanionEntity companion, ItemStack stack) {
        if (active || stack.isEmpty()) {
            return false;
        }
        this.item = stack;
        this.originalCount = stack.getCount();
        this.food = stack.get(DataComponents.FOOD);
        this.previous = companion.getMainHandItem().copy();
        companion.setItemSlot(EquipmentSlot.MAINHAND, stack);
        companion.startUsingItem(InteractionHand.MAIN_HAND);
        if (!companion.isUsingItem()) {
            restore(companion);
            return false;
        }
        this.hold = companion.taskExecution().acquireSelfCareHold();
        this.ticks = 0;
        this.active = true;
        return true;
    }

    /** Advances a running use; true while it still owns the hands. */
    boolean tick(CompanionEntity companion) {
        if (!active) {
            return false;
        }
        if (companion.isUsingItem() && ++ticks < MAX_USE_TICKS) {
            return true;
        }
        if (companion.isUsingItem()) {
            companion.stopUsingItem();
        }
        ItemStack left = companion.getMainHandItem();
        boolean consumed = left.isEmpty() || left.getCount() < originalCount;
        if (consumed && food != null) {
            companion.hunger().eat(food.nutrition(), food.saturation());
        }
        restore(companion);
        return true;
    }

    /** Ends a use in progress (stop, dismiss) and gives the item back. */
    void stop(CompanionEntity companion) {
        if (!active) {
            return;
        }
        if (companion.isUsingItem()) {
            companion.stopUsingItem();
        }
        restore(companion);
    }

    private void restore(CompanionEntity companion) {
        ItemStack left = companion.getMainHandItem();
        companion.setItemSlot(EquipmentSlot.MAINHAND, previous);
        if (!left.isEmpty()) {
            InventoryOps.insertOrDrop(companion, left);
        }
        active = false;
        item = ItemStack.EMPTY;
        previous = ItemStack.EMPTY;
        food = null;
        originalCount = 0;
        ticks = 0;
        if (hold != null) {
            hold.close();
            hold = null;
        }
    }
}
