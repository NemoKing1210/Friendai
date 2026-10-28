package dev.friendai.task.builtin;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.placed.PlacedService;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.interact.ContainerAccess;
import dev.friendai.task.inventory.InventoryOps;
import dev.friendai.task.inventory.ItemMatcher;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

/** Puts items from the 27-slot inventory into a storage container. Never touches the main hand. */
public final class StoreItemsTask extends ContainerTaskBase {
    /** How far from the companion a placed storage container may be. */
    private static final int NEAR_PLACED_RADIUS = 64;

    private final ItemMatcher matcher;
    private final int count;
    /** When true, the target is the nearest storage container the companion placed. */
    private final boolean placed;

    public StoreItemsTask(ItemMatcher matcher, int count, BlockPos target) {
        this(matcher, count, target, false);
    }

    public StoreItemsTask(ItemMatcher matcher, int count, BlockPos target, boolean placed) {
        super(target);
        this.matcher = matcher;
        this.count = count;
        this.placed = placed;
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
        if (placed) {
            return PlacedService.nearestContainer(companion, NEAR_PLACED_RADIUS);
        }
        return ContainerAccess.nearest(companion, RADIUS);
    }

    @Override
    protected String noTargetSummary() {
        return placed
                ? "no storage container you placed within " + NEAR_PLACED_RADIUS + " blocks"
                : "no storage container within " + RADIUS + " blocks";
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
        if (placed) {
            return matcher == null
                    ? "storing items in a container I placed"
                    : "storing " + matcher.label() + " in a container I placed";
        }
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
