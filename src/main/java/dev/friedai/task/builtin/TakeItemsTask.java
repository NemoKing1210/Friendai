package dev.friedai.task.builtin;

import dev.friedai.entity.CompanionEntity;
import dev.friedai.task.TaskContext;
import dev.friedai.task.TaskStatus;
import dev.friedai.task.interact.ContainerAccess;
import dev.friedai.task.inventory.InventoryOps;
import dev.friedai.task.inventory.ItemMatcher;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

/** Takes items from a storage container into the companion's inventory. */
public final class TakeItemsTask extends ContainerTaskBase {
    private final ItemMatcher matcher;
    private final int count;

    public TakeItemsTask(ItemMatcher matcher, int count, BlockPos target) {
        super(target);
        this.matcher = matcher;
        this.count = count;
    }

    @Override
    public String type() {
        return "take_items";
    }

    @Override
    public void start(TaskContext c) {}

    @Override
    protected Optional<String> preflight(TaskContext c) {
        return canFit(c.companion()) ? Optional.empty() : Optional.of("my inventory is full");
    }

    @Override
    protected Optional<BlockPos> findTarget(ServerLevel level, CompanionEntity companion) {
        return ContainerAccess.nearest(level, companion.blockPosition(), RADIUS,
                container -> InventoryOps.count(container, matcher) > 0);
    }

    @Override
    protected String noTargetSummary() {
        return "no storage container with " + matcher.label() + " within " + RADIUS + " blocks";
    }

    @Override
    protected String missingTargetSummary() {
        return "there is no chest, barrel or other storage at " + coords();
    }

    @Override
    protected TaskStatus work(TaskContext c) {
        var companion = c.companion();
        var inventory = companion.inventory();
        String containerId = ContainerAccess.label(c.level(), containerPos);
        String prose = ContainerAccess.shortLabel(c.level(), containerPos);
        if (InventoryOps.count(container, matcher) == 0) {
            summary = "the " + prose + " at " + coords() + " has no " + matcher.label();
            return TaskStatus.FAILED;
        }

        int wanted = count;
        int taken = 0;
        boolean full = false;
        for (int slot = 0; slot < container.getContainerSize() && taken < wanted && !full; slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.isEmpty() || !matcher.test(stack)) {
                continue;
            }
            int take = Math.min(wanted - taken, stack.getCount());
            ItemStack removed = container.removeItem(slot, take);
            int before = removed.getCount();
            ItemStack leftover = inventory.addItem(removed);
            int moved = before - leftover.getCount();
            taken += moved;
            if (!leftover.isEmpty()) {
                ItemStack current = container.getItem(slot);
                if (current.isEmpty()) {
                    container.setItem(slot, leftover);
                } else {
                    current.grow(leftover.getCount());
                }
                full = true;
            }
        }
        container.setChanged();
        inventory.setChanged();

        if (taken == 0) {
            summary = full
                    ? "my inventory is full"
                    : "the " + prose + " at " + coords() + " has no " + matcher.label();
            return TaskStatus.FAILED;
        }
        if (taken < wanted) {
            String reason = full ? "my inventory is full" : "that was all it had";
            summary = "took only " + taken + " of " + wanted + " " + matcher.label() + " from " + containerId
                    + " at " + coords() + " (" + reason + ")";
        } else {
            summary = "took " + taken + " " + matcher.label() + " from " + containerId + " at " + coords();
        }
        return TaskStatus.SUCCESS;
    }

    @Override
    public String describe() {
        return "taking " + count + " " + matcher.label() + " from a container";
    }

    private boolean canFit(CompanionEntity companion) {
        for (int slot = 0; slot < companion.inventory().getContainerSize(); slot++) {
            ItemStack stack = companion.inventory().getItem(slot);
            if (stack.isEmpty()) {
                return true;
            }
            if (matcher.test(stack) && stack.getCount() < stack.getMaxStackSize()) {
                return true;
            }
        }
        return false;
    }
}
