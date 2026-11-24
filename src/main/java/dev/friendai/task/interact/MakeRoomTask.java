package dev.friendai.task.interact;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

/**
 * Frees a measured number of inventory slots by putting unrelated carried stacks into one container the normal way:
 * it walks there, checks protection, opens the lid, deposits and records what the container now holds.
 *
 * <p>It moves no more than the deficit asks for and never touches an item in the keep set, so the owner's stock, the
 * material an order still needs and a reserved material stay in the bag. A refused opening moves nothing and leaves
 * {@link #failureKind()} set, so a caller can try another authorized container; a container that fills short of the
 * need reports what already left the bag.
 */
public final class MakeRoomTask implements Task {
    private final int needed;
    private final Set<String> keep;
    private final ContainerSession session;

    private int moved;
    private final Map<String, Integer> movedByItem = new LinkedHashMap<>();
    private String summary = "";

    public MakeRoomTask(BlockPos target, int needed, Set<String> keep) {
        if (target == null) throw new IllegalArgumentException("a container position is required");
        if (needed < 1) throw new IllegalArgumentException("needed slots must be positive");
        this.needed = needed;
        this.keep = keep == null ? Set.of() : Set.copyOf(keep);
        this.session = new ContainerSession(
                target,
                null,
                false,
                () -> "no storage container to make room in",
                position -> "there is no storage container at " + coords(position));
    }

    @Override
    public String type() {
        return "make_room";
    }

    @Override
    public void start(TaskContext c) {}

    @Override
    public TaskStatus tick(TaskContext c) {
        if (!summary.isEmpty()) return TaskStatus.FAILED;
        TaskStatus status = session.tick(c, this::deposit);
        if (status == TaskStatus.FAILED && summary.isEmpty()) summary = session.failureSummary();
        return status;
    }

    @Override
    public void stop(TaskContext c) {
        session.stop(c);
    }

    @Override
    public String resultSummary() {
        return summary;
    }

    @Override
    public String describe() {
        return "making room in a container for " + needed + " slot(s)";
    }

    /** How many items this task deposited, so the caller can say where the owner's stock went. */
    public int moved() {
        return moved;
    }

    /** Why the session could not reach or open the container; {@code NONE} when it did. */
    public ContainerSession.FailureKind failureKind() {
        return session.failureKind();
    }

    private TaskStatus deposit(TaskContext c, BlockPos position, Container container) {
        CompanionEntity companion = c.companion();
        for (int slot = 0; slot < companion.inventory().getContainerSize(); slot++) {
            if (emptySlots(companion) >= needed) break;
            ItemStack stack = companion.inventory().getItem(slot);
            if (stack.isEmpty()) continue;
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            if (keep.contains(id)) continue;
            int before = stack.getCount();
            ItemStack leftover = ContainerAccess.insert(container, stack.copy());
            int stored = before - leftover.getCount();
            if (stored <= 0) continue;
            stack.shrink(stored);
            if (stack.isEmpty()) companion.inventory().setItem(slot, ItemStack.EMPTY);
            moved += stored;
            movedByItem.merge(id, stored, Integer::sum);
        }
        companion.inventory().setChanged();
        container.setChanged();
        ContainerAccess.recordContents(c, position, container);

        if (emptySlots(companion) >= needed) {
            summary = movedText(c, position);
            return TaskStatus.SUCCESS;
        }
        summary = moved == 0
                ? "the " + ContainerAccess.shortLabel(c.level(), position) + " at " + coords(position) + " is full"
                : movedText(c, position) + "; the "
                        + ContainerAccess.shortLabel(c.level(), position)
                        + " at "
                        + coords(position)
                        + " is full";
        return TaskStatus.FAILED;
    }

    private String movedText(TaskContext c, BlockPos position) {
        StringBuilder text = new StringBuilder("stored ");
        boolean first = true;
        for (Map.Entry<String, Integer> entry : movedByItem.entrySet()) {
            if (!first) text.append(", ");
            text.append(entry.getValue()).append(' ').append(entry.getKey());
            first = false;
        }
        return text.append(" in ")
                .append(ContainerAccess.label(c.level(), position))
                .append(" at ")
                .append(coords(position))
                .append(" to make room")
                .toString();
    }

    private static int emptySlots(CompanionEntity companion) {
        int empty = 0;
        for (int slot = 0; slot < companion.inventory().getContainerSize(); slot++) {
            if (companion.inventory().getItem(slot).isEmpty()) empty++;
        }
        return empty;
    }

    private static String coords(BlockPos position) {
        return position.getX() + " " + position.getY() + " " + position.getZ();
    }
}
