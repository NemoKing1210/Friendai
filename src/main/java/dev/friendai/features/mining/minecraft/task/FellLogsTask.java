package dev.friendai.features.mining.minecraft.task;

import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.inventory.InventoryOps;
import dev.friendai.task.inventory.ItemMatcher;
import dev.friendai.world.block.BlockMatcher;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;

/**
 * Fells whole trees until the bag holds the number of logs asked for. One tree gives what it has; when that is not
 * enough the next tree is felled, and the count miner is the fallback when no tree is left. Felling a whole tree is the
 * point: stopping after the few logs a recipe needs leaves the rest of the trunk standing.
 */
public final class FellLogsTask implements Task {
    /** How many trees one request may fell before the count miner takes over. */
    public static final int DEFAULT_MAX_TREES = 8;
    /** How long one request may run before it reports what it gathered, in ticks. */
    public static final int DEFAULT_MAX_TICKS = 24_000;

    private final BlockMatcher matcher;
    private final int count;
    private final int radius;
    private final boolean collect;

    private BlockPos origin;
    private Optional<ItemMatcher> itemMatcher = Optional.empty();
    private Task current;
    private int trees;
    private int gained;
    private int felled;
    private int before;
    private int ticks;
    private boolean fallbackUsed;
    private String summary = "";

    public FellLogsTask(BlockMatcher matcher, int count, int radius, boolean collect) {
        this.matcher = matcher;
        this.count = Math.max(1, count);
        this.radius = Math.max(1, radius);
        this.collect = collect;
    }

    @Override
    public String type() {
        return "obtain_logs";
    }

    @Override
    public boolean hasTimeout() {
        return false;
    }

    @Override
    public void start(TaskContext c) {
        origin = c.companion().blockPosition().immutable();
        itemMatcher = ItemMatcher.parse(matcher.label());
        current = null;
        trees = 0;
        gained = 0;
        felled = 0;
        before = counted(c);
        ticks = 0;
        fallbackUsed = false;
        summary = "";
    }

    @Override
    public TaskStatus tick(TaskContext c) {
        if (++ticks > DEFAULT_MAX_TICKS) {
            return fail("ran out of time; cut " + gained + " of " + count + " " + matcher.label());
        }
        if (current != null) {
            TaskStatus status = current.tick(c);
            if (status == TaskStatus.RUNNING) {
                return TaskStatus.RUNNING;
            }
            gained += Math.max(0, counted(c) - before);
            summary = current.resultSummary();
            safeStop(current, c);
            current = null;
            if (gained >= count) {
                return succeed();
            }
            if (fallbackUsed) {
                return fail("cut " + gained + " of " + count + " " + matcher.label() + ": " + summary);
            }
            if (status == TaskStatus.FAILED) {
                return startFallback(c);
            }
        }
        if (trees >= DEFAULT_MAX_TREES) {
            return startFallback(c);
        }
        before = counted(c);
        current = new FellTreeSearchTask(matcher, origin, radius, 0, collect, true);
        current.start(c);
        trees++;
        return TaskStatus.RUNNING;
    }

    private TaskStatus startFallback(TaskContext c) {
        if (fallbackUsed) {
            return fail("cut " + gained + " of " + count + " " + matcher.label() + ": " + summary);
        }
        fallbackUsed = true;
        felled = gained;
        before = counted(c);
        current = new MineBlocksTask(matcher, Math.max(1, count - gained), radius, origin, collect);
        current.start(c);
        return TaskStatus.RUNNING;
    }

    private TaskStatus succeed() {
        summary = fallbackUsed
                ? "gathered " + gained + " " + matcher.label() + " logs (" + felled + " felled, " + (gained - felled)
                        + " mined)"
                : "felled " + gained + " " + matcher.label() + " logs from " + trees
                        + (trees == 1 ? " tree" : " trees");
        return TaskStatus.SUCCESS;
    }

    private TaskStatus fail(String reason) {
        summary = reason;
        return TaskStatus.FAILED;
    }

    private int counted(TaskContext c) {
        return itemMatcher
                .map(matcher -> InventoryOps.count(c.companion().inventory(), matcher))
                .orElse(0);
    }

    /** Stops a child task without letting a failing stop take the whole request down with it. */
    private static void safeStop(Task task, TaskContext c) {
        try {
            task.stop(c);
        } catch (RuntimeException ignored) {
            // A task that cannot stop cleanly must not take the whole gather down with it.
        }
    }

    @Override
    public void stop(TaskContext c) {
        if (current != null) {
            safeStop(current, c);
            current = null;
        }
        c.companion().getNavigation().stop();
    }

    @Override
    public void onTerminal(TaskContext c) {
        if (current != null) {
            safeStop(current, c);
            current = null;
        }
    }

    @Override
    public Task cleanupAfterCancel(TaskContext c) {
        return current == null ? null : current.cleanupAfterCancel(c);
    }

    @Override
    public Map<String, Integer> materialNeeds(TaskContext context) {
        return current == null ? Map.of() : current.materialNeeds(context);
    }

    @Override
    public String describe() {
        return current == null ? "looking for a " + matcher.label() + " tree" : current.describe();
    }

    @Override
    public String debugStatus() {
        return "trees=" + trees + "/" + DEFAULT_MAX_TREES + " gained=" + gained + "/" + count
                + (current == null ? "" : " | " + current.debugStatus());
    }

    @Override
    public String resultSummary() {
        return summary;
    }
}
