package dev.friendai.task.builtin;

import dev.friendai.features.creative.CreativeAction;
import dev.friendai.features.creative.CreativeItemCount;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import java.util.Optional;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/** Delivers one bounded stack of a registered item's default instance per task tick. */
public final class CreateItemsTask implements Task {
    public enum Destination {
        SELF,
        OWNER
    }

    public static final double OWNER_DELIVERY_RADIUS = 16.0;

    private final ItemStack prototype;
    private final long requested;
    private final Destination destination;
    private long delivered;
    private String summary = "";

    public CreateItemsTask(ItemStack prototype, long requested, Destination destination) {
        if (prototype == null || prototype.isEmpty()) {
            throw new IllegalArgumentException("item must be a registered non-air item");
        }
        if (requested < 1) {
            throw new IllegalArgumentException("count must be greater than zero");
        }
        if (destination == null) {
            throw new IllegalArgumentException("destination is required");
        }
        this.prototype = prototype.copyWithCount(1);
        this.requested = requested;
        this.destination = destination;
    }

    @Override
    public String type() {
        return "create_item";
    }

    @Override
    public void start(TaskContext context) {}

    @Override
    public TaskStatus tick(TaskContext context) {
        if (delivered >= requested) {
            summary = result("complete");
            return TaskStatus.SUCCESS;
        }
        if (!context.canUseCreativeAction(CreativeAction.CREATE_ITEM)) {
            summary = result("the owner's creative permission is no longer active");
            return TaskStatus.FAILED;
        }
        Optional<Container> target = destination(context);
        if (target.isEmpty()) {
            summary = result(
                    destination == Destination.OWNER
                            ? "the owner must be online within 16 blocks in this dimension"
                            : "the companion inventory is unavailable");
            return TaskStatus.FAILED;
        }

        Container inventory = target.get();
        long capacity = capacity(inventory, prototype);
        CreativeItemCount.Delivery plan = CreativeItemCount.plan(requested - delivered, capacity);
        if (plan.delivered() == 0) {
            summary = result("the destination inventory is full");
            return TaskStatus.FAILED;
        }

        int stackSize = Math.min(prototype.getMaxStackSize(), inventory.getMaxStackSize(prototype));
        int batch = (int) Math.min(plan.delivered(), stackSize);
        ItemStack stack = prototype.copyWithCount(batch);
        int inserted = insert(inventory, stack);
        delivered += inserted;
        if (inserted != batch) {
            summary = result("the destination inventory filled before this batch completed");
            return TaskStatus.FAILED;
        }
        if (delivered == requested) {
            summary = result("complete");
            return TaskStatus.SUCCESS;
        }
        return TaskStatus.RUNNING;
    }

    @Override
    public void stop(TaskContext context) {}

    @Override
    public String describe() {
        return "creating " + requested + " " + itemName() + " for "
                + (destination == Destination.SELF ? "my inventory" : "the owner's inventory");
    }

    @Override
    public String resultSummary() {
        return summary;
    }

    public long delivered() {
        return delivered;
    }

    public long remaining() {
        return requested - delivered;
    }

    private Optional<Container> destination(TaskContext context) {
        if (destination == Destination.SELF) {
            return Optional.of(context.companion().inventory());
        }
        ServerPlayer owner = context.companion().owner().orElse(null);
        if (owner == null
                || owner.level() != context.companion().level()
                || context.companion().distanceToSqr(owner) > OWNER_DELIVERY_RADIUS * OWNER_DELIVERY_RADIUS) {
            return Optional.empty();
        }
        return Optional.of(owner.getInventory());
    }

    private static long capacity(Container inventory, ItemStack prototype) {
        int slots = inventory instanceof Inventory playerInventory
                ? playerInventory.getNonEquipmentItems().size()
                : inventory.getContainerSize();
        long empty = 0;
        long compatibleRoom = 0;
        for (int slot = 0; slot < slots; slot++) {
            ItemStack current = inventory.getItem(slot);
            if (current.isEmpty()) {
                empty++;
            } else if (ItemStack.isSameItemSameComponents(current, prototype)) {
                int slotMax = Math.min(current.getMaxStackSize(), inventory.getMaxStackSize(current));
                compatibleRoom += Math.max(0, slotMax - current.getCount());
            }
        }
        int stackMax = Math.min(prototype.getMaxStackSize(), inventory.getMaxStackSize(prototype));
        return CreativeItemCount.capacity(empty, stackMax, compatibleRoom);
    }

    /** Inserts only into main inventory slots; any remainder stays with the task for an accurate partial result. */
    private static int insert(Container inventory, ItemStack stack) {
        int slots = inventory instanceof Inventory playerInventory
                ? playerInventory.getNonEquipmentItems().size()
                : inventory.getContainerSize();
        int requested = stack.getCount();
        for (int slot = 0; slot < slots && !stack.isEmpty(); slot++) {
            ItemStack current = inventory.getItem(slot);
            if (current.isEmpty() || !ItemStack.isSameItemSameComponents(current, stack)) {
                continue;
            }
            int slotMax = Math.min(current.getMaxStackSize(), inventory.getMaxStackSize(current));
            int moved = Math.min(stack.getCount(), Math.max(0, slotMax - current.getCount()));
            if (moved > 0) {
                current.grow(moved);
                stack.shrink(moved);
            }
        }
        for (int slot = 0; slot < slots && !stack.isEmpty(); slot++) {
            if (!inventory.getItem(slot).isEmpty()) {
                continue;
            }
            int slotMax = Math.min(stack.getMaxStackSize(), inventory.getMaxStackSize(stack));
            int moved = Math.min(stack.getCount(), slotMax);
            inventory.setItem(slot, stack.copyWithCount(moved));
            stack.shrink(moved);
        }
        inventory.setChanged();
        return requested - stack.getCount();
    }

    private String itemName() {
        return net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(prototype.getItem())
                .toString();
    }

    private String result(String detail) {
        return "created " + delivered + " of " + requested + " " + itemName() + "; " + remaining() + " remaining ("
                + detail + ")";
    }
}
