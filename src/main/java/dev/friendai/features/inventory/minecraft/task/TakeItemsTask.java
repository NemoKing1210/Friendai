package dev.friendai.features.inventory.minecraft.task;

import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.interact.ContainerAccess;
import dev.friendai.task.interact.ContainerTransfer;
import dev.friendai.task.inventory.ItemMatcher;
import java.util.Optional;
import net.minecraft.core.BlockPos;

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
        return ContainerTransfer.canFit(c.companion(), matcher)
                ? Optional.empty()
                : Optional.of("my inventory is full");
    }

    @Override
    protected Optional<BlockPos> findTarget(TaskContext context) {
        return ContainerAccess.nearest(context, RADIUS);
    }

    @Override
    protected boolean onlyInsidePlacesInFreeTime() {
        return true;
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
        ContainerTransfer.Result result = ContainerTransfer.take(c, containerPos, container, matcher, count);
        summary = result.summary();
        return result.status();
    }

    @Override
    public String describe() {
        return "taking " + count + " " + matcher.label() + " from a container";
    }
}
