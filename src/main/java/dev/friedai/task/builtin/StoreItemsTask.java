package dev.friedai.task.builtin;

import dev.friedai.entity.CompanionEntity;
import dev.friedai.task.TaskContext;
import dev.friedai.task.TaskStatus;
import dev.friedai.task.interact.ContainerAccess;
import dev.friedai.task.inventory.InventoryOps;
import dev.friedai.task.inventory.ItemMatcher;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

/** Puts items from the 27-slot inventory into a storage container. Never touches the main hand. */
public final class StoreItemsTask extends ContainerTaskBase {
    private final ItemMatcher matcher;
    private final int count;

    public StoreItemsTask(ItemMatcher matcher, int count, BlockPos target) {
        super(target);
        this.matcher = matcher;
        this.count = count;
    }

    @Override
    public String type() {
        return "store_items";
    }

    @Override
    public void start(TaskContext c) {}

    @Override
    protected Optional<String> preflight(TaskContext c) {
        if (matcher == null) {
            return totalItems(c.companion()) == 0 ? Optional.of("I have nothing to store") : Optional.empty();
        }
        return InventoryOps.count(c.companion().inventory(), matcher) == 0
                ? Optional.of("I don't have " + matcher.label())
                : Optional.empty();
    }

    @Override
    protected Optional<BlockPos> findTarget(ServerLevel level, CompanionEntity companion) {
        return ContainerAccess.nearest(level, companion.blockPosition(), RADIUS);
    }

    @Override
    protected String noTargetSummary() {
        return "no storage container within " + RADIUS + " blocks";
    }

    @Override
    protected String missingTargetSummary() {
        return "there is no chest, barrel or other storage at " + coords();
    }

    @Override
    protected TaskStatus work(TaskContext c) {
        var inventory = c.companion().inventory();
        Map<String, Integer> stored = new LinkedHashMap<>();
        int remaining = count == Integer.MAX_VALUE ? Integer.MAX_VALUE : count;
        for (int slot = 0; slot < inventory.getContainerSize() && remaining > 0; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || (matcher != null && !matcher.test(stack))) {
                continue;
            }
            int take = Math.min(remaining, stack.getCount());
            ItemStack moving = stack.copyWithCount(take);
            String id = BuiltInRegistries.ITEM.getKey(moving.getItem()).toString();
            ItemStack leftover = ContainerAccess.insert(container, moving);
            int moved = take - leftover.getCount();
            if (moved > 0) {
                stack.shrink(moved);
                if (stack.isEmpty()) {
                    inventory.setItem(slot, ItemStack.EMPTY);
                }
                stored.merge(id, moved, Integer::sum);
                remaining -= moved;
            }
        }
        inventory.setChanged();
        container.setChanged();

        if (stored.isEmpty()) {
            summary = "the " + ContainerAccess.shortLabel(c.level(), containerPos) + " at " + coords() + " is full";
            return TaskStatus.FAILED;
        }
        StringBuilder text = new StringBuilder("stored ");
        boolean first = true;
        for (Map.Entry<String, Integer> entry : stored.entrySet()) {
            if (!first) {
                text.append(", ");
            }
            text.append(entry.getValue()).append(' ').append(entry.getKey());
            first = false;
        }
        text.append(" in ").append(ContainerAccess.label(c.level(), containerPos)).append(" at ").append(coords());
        int left = matcher == null ? totalItems(c.companion()) : InventoryOps.count(c.companion().inventory(), matcher);
        if (remaining > 0 && left > 0) {
            text.append("; the ").append(ContainerAccess.shortLabel(c.level(), containerPos)).append(" is full");
        }
        summary = text.toString();
        return TaskStatus.SUCCESS;
    }

    @Override
    public String describe() {
        return matcher == null
                ? "storing items in a container"
                : "storing " + matcher.label() + " in a container";
    }

    private static int totalItems(CompanionEntity companion) {
        int total = 0;
        for (int slot = 0; slot < companion.inventory().getContainerSize(); slot++) {
            total += companion.inventory().getItem(slot).getCount();
        }
        return total;
    }
}
