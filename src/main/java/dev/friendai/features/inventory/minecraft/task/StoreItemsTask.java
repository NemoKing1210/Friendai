package dev.friendai.features.inventory.minecraft.task;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.interact.ContainerAccess;
import dev.friendai.task.inventory.InventoryOps;
import dev.friendai.task.inventory.ItemMatcher;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;

/** Puts items from the 27-slot inventory into a storage container. Never touches the main hand. */
public final class StoreItemsTask extends ContainerTaskBase {
    /** How far from the companion a placed storage container may be. */
    private static final int NEAR_PLACED_RADIUS = 64;

    private final ItemMatcher matcher;
    private final int count;
    /** When true, the target is the nearest storage container the companion placed. */
    private final boolean placed;

    private final Set<BlockPos> tried = new LinkedHashSet<>();
    private final List<String> full = new ArrayList<>();

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
    protected Optional<BlockPos> findTarget(TaskContext context) {
        if (placed) {
            return context.placedService().nearestContainer(context.companion(), NEAR_PLACED_RADIUS);
        }
        for (BlockPos candidate : context.storage().storeTargets(context.companion(), RADIUS, carriedIds(context))) {
            if (markTried(context, candidate)) {
                return Optional.of(candidate);
            }
        }
        for (BlockPos candidate : ContainerAccess.known(context, RADIUS)) {
            if (markTried(context, candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /** A position already opened in this task is not offered again; a double chest counts once, by its canonical half. */
    private boolean markTried(TaskContext context, BlockPos candidate) {
        return tried.add(ContainerAccess.canonicalChest(context.level(), candidate));
    }

    /** The item ids the carried stacks this task would store belong to, for judging partial-stack room. */
    private Set<String> carriedIds(TaskContext context) {
        Set<String> ids = new LinkedHashSet<>();
        SimpleContainer inventory = context.companion().inventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            if (matcher == null || matcher.accepts(id)) {
                ids.add(id);
            }
        }
        return ids;
    }

    @Override
    protected String noTargetSummary() {
        if (!full.isEmpty()) {
            return fullText();
        }
        return placed
                ? "no storage container you placed within " + NEAR_PLACED_RADIUS + " blocks"
                : "no storage container within " + RADIUS + " blocks";
    }

    private String fullText() {
        if (full.size() == 1) {
            return full.getFirst() + " is full";
        }
        return String.join(", ", full.subList(0, full.size() - 1)) + " and " + full.getLast() + " are full";
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
        ContainerAccess.recordContents(c, containerPos, container);

        if (stored.isEmpty()) {
            String filled = "the " + ContainerAccess.shortLabel(c.level(), containerPos) + " at " + coords();
            if (!full.contains(filled)) {
                full.add(filled);
            }
            summary = fullText();
            if (!placed) {
                tryAnotherContainer();
            }
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
        text.append(" in ")
                .append(ContainerAccess.label(c.level(), containerPos))
                .append(" at ")
                .append(coords());
        int left = matcher == null
                ? totalItems(c.companion())
                : InventoryOps.count(c.companion().inventory(), matcher);
        if (remaining > 0 && left > 0) {
            text.append("; the ")
                    .append(ContainerAccess.shortLabel(c.level(), containerPos))
                    .append(" is full");
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
        return matcher == null ? "storing items in a container" : "storing " + matcher.label() + " in a container";
    }

    private static int totalItems(CompanionEntity companion) {
        int total = 0;
        for (int slot = 0; slot < companion.inventory().getContainerSize(); slot++) {
            total += companion.inventory().getItem(slot).getCount();
        }
        return total;
    }
}
