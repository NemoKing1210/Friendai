package dev.friendai.task.interact;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.inventory.InventoryOps;
import dev.friendai.task.inventory.ItemMatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

/** Moves a bounded number of matching items from an already open container into the companion's inventory. */
public final class ContainerTransfer {
    public record Result(TaskStatus status, String summary, int moved) {}

    private ContainerTransfer() {}

    public static Result take(
            TaskContext context, BlockPos position, Container container, ItemMatcher matcher, int requested) {
        CompanionEntity companion = context.companion();
        String containerId = ContainerAccess.label(context.level(), position);
        String prose = ContainerAccess.shortLabel(context.level(), position);
        if (InventoryOps.count(container, matcher) == 0) {
            return new Result(
                    TaskStatus.FAILED, "the " + prose + " at " + coords(position) + " has no " + matcher.label(), 0);
        }

        int taken = 0;
        boolean full = false;
        for (int slot = 0; slot < container.getContainerSize() && taken < requested && !full; slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.isEmpty() || !matcher.test(stack)) continue;
            int take = Math.min(requested - taken, stack.getCount());
            ItemStack removed = container.removeItem(slot, take);
            int before = removed.getCount();
            ItemStack leftover = companion.inventory().addItem(removed);
            taken += before - leftover.getCount();
            if (!leftover.isEmpty()) {
                ItemStack current = container.getItem(slot);
                if (current.isEmpty()) container.setItem(slot, leftover);
                else current.grow(leftover.getCount());
                full = true;
            }
        }
        container.setChanged();
        companion.inventory().setChanged();

        if (taken == 0) {
            return new Result(
                    TaskStatus.FAILED,
                    full
                            ? "my inventory is full"
                            : "the " + prose + " at " + coords(position) + " has no " + matcher.label(),
                    0);
        }
        if (taken < requested) {
            String reason = full ? "my inventory is full" : "that was all it had";
            return new Result(
                    TaskStatus.SUCCESS,
                    "took only " + taken + " of " + requested + " " + matcher.label() + " from " + containerId + " at "
                            + coords(position) + " (" + reason + ")",
                    taken);
        }
        return new Result(
                TaskStatus.SUCCESS,
                "took " + taken + " " + matcher.label() + " from " + containerId + " at " + coords(position),
                taken);
    }

    public static boolean canFit(CompanionEntity companion, ItemMatcher matcher) {
        for (int slot = 0; slot < companion.inventory().getContainerSize(); slot++) {
            ItemStack stack = companion.inventory().getItem(slot);
            if (stack.isEmpty() || matcher.test(stack) && stack.getCount() < stack.getMaxStackSize()) return true;
        }
        return false;
    }

    private static String coords(BlockPos position) {
        return position.getX() + " " + position.getY() + " " + position.getZ();
    }
}
