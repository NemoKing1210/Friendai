package dev.friendai.features.inventory.minecraft.task;

import dev.friendai.debug.DebugTarget;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.interact.ContainerAccess;
import dev.friendai.task.interact.ContainerTakeRun;
import dev.friendai.task.inventory.ItemMatcher;
import java.util.LinkedHashSet;
import java.util.List;
import net.minecraft.core.BlockPos;

/**
 * Takes items from a storage container into the companion's inventory. Explicit coordinates pin the task to that
 * container; without them it walks a bounded list of eligible containers, live records first, until the asked amount
 * is in the bag or every candidate has refused.
 */
public final class TakeItemsTask implements Task {
    private static final int RADIUS = 16;

    private final ContainerTakeRun run;

    public TakeItemsTask(ItemMatcher matcher, int count, BlockPos target) {
        this.run = new ContainerTakeRun(
                matcher,
                count,
                target,
                context -> ContainerAccess.withdrawals(context, matcher::accepts, RADIUS),
                true,
                () -> "no storage container with " + matcher.label() + " within " + RADIUS + " blocks",
                new LinkedHashSet<>());
    }

    @Override
    public String type() {
        return "take_items";
    }

    @Override
    public void start(TaskContext c) {
        run.start(c);
    }

    @Override
    public TaskStatus tick(TaskContext c) {
        return run.tick(c);
    }

    @Override
    public void stop(TaskContext c) {
        run.stop(c);
    }

    @Override
    public String describe() {
        return run.describe();
    }

    @Override
    public String resultSummary() {
        return run.resultSummary();
    }

    @Override
    public String debugStatus() {
        return run.debugStatus();
    }

    @Override
    public List<DebugTarget> debugTargets() {
        return run.debugTargets();
    }
}
