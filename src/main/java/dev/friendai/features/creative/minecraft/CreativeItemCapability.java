package dev.friendai.features.creative.minecraft;

import dev.friendai.features.creative.CreativeAction;
import dev.friendai.features.creative.CreativeItemCount;
import dev.friendai.task.TaskContext;
import java.util.Optional;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/** Shared, bounded creative item delivery for direct creative tools and acquisition. */
public final class CreativeItemCapability {
    public enum Destination {
        COMPANION,
        OWNER
    }

    public enum Failure {
        NONE,
        UNAUTHORIZED,
        DESTINATION_UNAVAILABLE,
        INVENTORY_FULL,
        PARTIAL_INSERTION
    }

    public record Batch(int inserted, Failure failure) {
        public Batch {
            if (inserted < 0) throw new IllegalArgumentException("inserted count cannot be negative");
            if (failure == null) throw new IllegalArgumentException("failure is required");
        }

        public boolean succeeded() {
            return failure == Failure.NONE;
        }
    }

    public static final double OWNER_DELIVERY_RADIUS = 16.0;

    private CreativeItemCapability() {}

    /** Delivers at most one stack. Call once per task tick and keep the uninserted remainder with the caller. */
    public static Batch deliverOneBatch(
            TaskContext context, ItemStack prototype, long remaining, Destination destination) {
        if (remaining < 1) throw new IllegalArgumentException("remaining count must be greater than zero");
        if (prototype == null || prototype.isEmpty()) throw new IllegalArgumentException("item is required");
        if (destination == null) throw new IllegalArgumentException("destination is required");
        if (!context.canUseCreativeAction(CreativeAction.CREATE_ITEM)) return new Batch(0, Failure.UNAUTHORIZED);

        Optional<Container> target = destination(context, destination);
        if (target.isEmpty()) return new Batch(0, Failure.DESTINATION_UNAVAILABLE);

        Container inventory = target.get();
        long capacity = capacity(inventory, prototype);
        CreativeItemCount.Delivery plan = CreativeItemCount.plan(remaining, capacity);
        if (plan.delivered() == 0) return new Batch(0, Failure.INVENTORY_FULL);

        int stackSize = Math.min(prototype.getMaxStackSize(), inventory.getMaxStackSize(prototype));
        int batch = (int) Math.min(plan.delivered(), stackSize);
        int inserted = insert(inventory, prototype.copyWithCount(batch));
        return inserted == batch ? new Batch(inserted, Failure.NONE) : new Batch(inserted, Failure.PARTIAL_INSERTION);
    }

    public static String failureText(Failure failure, Destination destination) {
        return switch (failure) {
            case NONE -> "complete";
            case UNAUTHORIZED -> "the owner's creative permission is no longer active";
            case DESTINATION_UNAVAILABLE ->
                destination == Destination.OWNER
                        ? "the owner must be online within 16 blocks in this dimension"
                        : "the companion inventory is unavailable";
            case INVENTORY_FULL -> "the destination inventory is full";
            case PARTIAL_INSERTION -> "the destination inventory filled before this batch completed";
        };
    }

    public static String resultSummary(ItemStack prototype, long delivered, long requested, String detail) {
        String itemName = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(prototype.getItem())
                .toString();
        return "created " + delivered + " of " + requested + " " + itemName + "; " + (requested - delivered)
                + " remaining (" + detail + ")";
    }

    private static Optional<Container> destination(TaskContext context, Destination destination) {
        if (destination == Destination.COMPANION)
            return Optional.of(context.companion().inventory());
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

    /** Inserts only into main inventory slots; any remainder stays with the caller for an accurate partial result. */
    private static int insert(Container inventory, ItemStack stack) {
        int slots = inventory instanceof Inventory playerInventory
                ? playerInventory.getNonEquipmentItems().size()
                : inventory.getContainerSize();
        int requested = stack.getCount();
        for (int slot = 0; slot < slots && !stack.isEmpty(); slot++) {
            ItemStack current = inventory.getItem(slot);
            if (current.isEmpty() || !ItemStack.isSameItemSameComponents(current, stack)) continue;
            int slotMax = Math.min(current.getMaxStackSize(), inventory.getMaxStackSize(current));
            int moved = Math.min(stack.getCount(), Math.max(0, slotMax - current.getCount()));
            if (moved > 0) {
                current.grow(moved);
                stack.shrink(moved);
            }
        }
        for (int slot = 0; slot < slots && !stack.isEmpty(); slot++) {
            if (!inventory.getItem(slot).isEmpty()) continue;
            int slotMax = Math.min(stack.getMaxStackSize(), inventory.getMaxStackSize(stack));
            int moved = Math.min(stack.getCount(), slotMax);
            inventory.setItem(slot, stack.copyWithCount(moved));
            stack.shrink(moved);
        }
        inventory.setChanged();
        return requested - stack.getCount();
    }
}
