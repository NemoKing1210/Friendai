package dev.friendai.task.interact;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.inventory.InventoryOps;
import dev.friendai.task.inventory.ItemMatcher;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

/** Moves a bounded number of matching items from an already open container into the companion's inventory. */
public final class ContainerTransfer {
    /**
     * The outcome, including the stacks that actually left the container: one receipt per withdrawn portion, carrying
     * the item id, its components and the count, so the total is exactly {@code moved}. Receipts stay separate instead
     * of merging by identity because two portions of a low-stack-size item would not fit in one stack. A caller that
     * moves them on later must use these receipts, not the matcher, or it can deposit the companion's own stock
     * instead of what it withdrew.
     */
    public record Result(TaskStatus status, String summary, int moved, List<ItemStack> withdrawn) {
        public Result {
            withdrawn = withdrawn == null ? List.of() : List.copyOf(withdrawn);
        }

        public Result(TaskStatus status, String summary, int moved) {
            this(status, summary, moved, List.of());
        }
    }

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
        List<ItemStack> withdrawn = new ArrayList<>();
        for (int slot = 0; slot < container.getContainerSize() && taken < requested && !full; slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.isEmpty() || !matcher.test(stack)) continue;
            int take = Math.min(requested - taken, stack.getCount());
            ItemStack removed = container.removeItem(slot, take);
            ItemStack identity = removed.copyWithCount(1);
            int before = removed.getCount();
            ItemStack leftover = companion.inventory().addItem(removed);
            int accepted = before - leftover.getCount();
            taken += accepted;
            if (accepted > 0) record(withdrawn, identity, accepted);
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
                    taken,
                    withdrawn);
        }
        return new Result(
                TaskStatus.SUCCESS,
                "took " + taken + " " + matcher.label() + " from " + containerId + " at " + coords(position),
                taken,
                withdrawn);
    }

    /** Records one withdrawn portion. Receipts stay per-stack so every count is a real stack size. */
    private static void record(List<ItemStack> withdrawn, ItemStack identity, int count) {
        if (count <= 0) return;
        withdrawn.add(identity.copyWithCount(count));
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
