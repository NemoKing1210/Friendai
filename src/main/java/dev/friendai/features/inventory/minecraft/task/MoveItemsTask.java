package dev.friendai.features.inventory.minecraft.task;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.interact.ContainerAccess;
import dev.friendai.task.interact.ContainerSession;
import dev.friendai.task.interact.ContainerTransfer;
import dev.friendai.task.inventory.ItemMatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

/** Moves one bounded item count from one storage container to another, returning leftovers to the source. */
public final class MoveItemsTask implements Task {
    private enum Phase {
        TAKE,
        STORE,
        RETURN,
        DONE
    }

    private final ItemMatcher matcher;
    private final int count;
    private final BlockPos from;
    private final BlockPos to;

    private ContainerSession session;
    private Phase phase = Phase.TAKE;
    private int moved;
    private int inserted;
    private int leftover;
    private String failure;
    private String summary = "";
    private boolean finished;

    public MoveItemsTask(ItemMatcher matcher, int count, BlockPos from, BlockPos to) {
        if (matcher == null) throw new IllegalArgumentException("item is required");
        if (count < 1) throw new IllegalArgumentException("count must be positive");
        this.matcher = matcher;
        this.count = count;
        this.from = from;
        this.to = to;
    }

    @Override
    public String type() {
        return "move_items";
    }

    @Override
    public void start(TaskContext c) {}

    @Override
    public TaskStatus tick(TaskContext c) {
        if (finished) return TaskStatus.SUCCESS;
        if (from == null || to == null) {
            finished = true;
            summary = "give both container positions";
            return TaskStatus.FAILED;
        }
        if (from.equals(to)) {
            finished = true;
            summary = "the source and the target are the same container";
            return TaskStatus.FAILED;
        }
        if (phase == Phase.DONE) return finish();
        if (session == null) {
            session = new ContainerSession(
                    target(),
                    null,
                    false,
                    () -> "no storage container for move_items",
                    position -> "there is no storage container at " + coords(position));
        }
        TaskStatus status = session.tick(c, this::work);
        if (status == TaskStatus.RUNNING) return TaskStatus.RUNNING;
        session = null;
        if (status == TaskStatus.FAILED) {
            finished = true;
            return TaskStatus.FAILED;
        }
        switch (phase) {
            case TAKE -> phase = Phase.STORE;
            case STORE -> phase = leftover > 0 ? Phase.RETURN : Phase.DONE;
            case RETURN -> phase = Phase.DONE;
            case DONE -> {
                return finish();
            }
        }
        return phase == Phase.DONE ? finish() : TaskStatus.RUNNING;
    }

    @Override
    public void stop(TaskContext c) {
        if (session != null) {
            session.stop(c);
            session = null;
        }
    }

    @Override
    public String resultSummary() {
        return summary;
    }

    @Override
    public String describe() {
        return "moving " + count + " " + matcher.label() + " between containers";
    }

    private TaskStatus work(TaskContext c, BlockPos position, Container container) {
        return switch (phase) {
            case TAKE -> {
                ContainerTransfer.Result result =
                        ContainerTransfer.take(c, position, container, matcher, count);
                moved = result.moved();
                summary = result.summary();
                ContainerAccess.recordContents(c, position, container);
                yield result.status();
            }
            case STORE -> {
                inserted = insertMatching(c.companion(), container, moved);
                ContainerAccess.recordContents(c, position, container);
                leftover = moved - inserted;
                if (inserted == 0) {
                    failure = "the "
                            + ContainerAccess.shortLabel(c.level(), position)
                            + " at "
                            + coords(position)
                            + " is full";
                }
                yield TaskStatus.SUCCESS;
            }
            case RETURN -> {
                int returned = insertMatching(c.companion(), container, leftover);
                ContainerAccess.recordContents(c, position, container);
                leftover -= returned;
                yield TaskStatus.SUCCESS;
            }
            case DONE -> TaskStatus.SUCCESS;
        };
    }

    private int insertMatching(CompanionEntity companion, Container container, int limit) {
        int inserted = 0;
        for (int slot = 0; slot < companion.inventory().getContainerSize() && inserted < limit; slot++) {
            ItemStack stack = companion.inventory().getItem(slot);
            if (stack.isEmpty() || !matcher.test(stack)) {
                continue;
            }
            int take = Math.min(limit - inserted, stack.getCount());
            ItemStack leftoverStack = ContainerAccess.insert(container, stack.copyWithCount(take));
            int stored = take - leftoverStack.getCount();
            if (stored > 0) {
                stack.shrink(stored);
                if (stack.isEmpty()) {
                    companion.inventory().setItem(slot, ItemStack.EMPTY);
                }
                inserted += stored;
            }
            if (!leftoverStack.isEmpty()) {
                break;
            }
        }
        companion.inventory().setChanged();
        container.setChanged();
        return inserted;
    }

    private TaskStatus finish() {
        finished = true;
        if (inserted == 0 && failure != null) {
            summary = failure;
            return TaskStatus.FAILED;
        }
        summary = "moved " + inserted + " " + matcher.label() + " from the container at " + coords(from)
                + " to the container at " + coords(to);
        if (leftover > 0) {
            summary += "; " + leftover + " still in my inventory";
        }
        return TaskStatus.SUCCESS;
    }

    private BlockPos target() {
        return switch (phase) {
            case TAKE, RETURN -> from;
            case STORE, DONE -> to;
        };
    }

    private static String coords(BlockPos position) {
        return position.getX() + " " + position.getY() + " " + position.getZ();
    }
}
