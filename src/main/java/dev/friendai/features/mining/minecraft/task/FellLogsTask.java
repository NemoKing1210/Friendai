package dev.friendai.features.mining.minecraft.task;

import dev.friendai.execution.TaskWorkResult;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.inventory.InventoryOps;
import dev.friendai.task.inventory.ItemMatcher;
import dev.friendai.world.block.BlockMatcher;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;

/**
 * Fells whole trees until the bag holds the number of logs asked for. One tree gives what it has; when that is not
 * enough the next tree is felled, and the count miner is the fallback when no tree is left. Felling a whole tree is the
 * point: stopping after the few logs a recipe needs leaves the rest of the trunk standing.
 */
public final class FellLogsTask implements Task {
    /** How many trees one request may fell before the count miner takes over. */
    public static final int DEFAULT_MAX_TREES = 8;
    /** How many consecutive trees may yield no carried logs before the count miner takes over. */
    public static final int DEFAULT_MAX_EMPTY_TREES = 2;
    /** How long one request may run before it reports what it gathered, in ticks. */
    public static final int DEFAULT_MAX_TICKS = 24_000;
    /** Bound one retained child reason; the summary names at most three attempts. */
    private static final int MAX_ATTEMPT_REASON = 160;

    private final BlockMatcher matcher;
    private final int count;
    private final int radius;
    private final boolean collect;
    private final Set<BlockPos> failedBases = new LinkedHashSet<>();
    private final List<TreeAttempt> attempts = new ArrayList<>(DEFAULT_MAX_TREES);

    private BlockPos origin;
    private Optional<ItemMatcher> itemMatcher = Optional.empty();
    private Task current;
    private int trees;
    private int emptyTrees;
    private int consecutiveEmptyTrees;
    private int gained;
    private int felled;
    private int before;
    private int startCount;
    private int workVerified;
    private int ticks;
    private boolean fallbackUsed;
    private boolean workStarted;
    private String summary = "";
    private TaskWorkResult workResult;

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
        emptyTrees = 0;
        consecutiveEmptyTrees = 0;
        failedBases.clear();
        attempts.clear();
        gained = 0;
        felled = 0;
        startCount = counted(c);
        before = startCount;
        workVerified = 0;
        ticks = 0;
        fallbackUsed = false;
        summary = "";
        workStarted = true;
        workResult = null;
    }

    @Override
    public TaskStatus tick(TaskContext c) {
        TaskStatus status = tickWork(c);
        workVerified = Math.max(0, counted(c) - startCount);
        if (status != TaskStatus.RUNNING) workResult = createWorkResult(status);
        return status;
    }

    private TaskStatus tickWork(TaskContext c) {
        if (++ticks > DEFAULT_MAX_TICKS) {
            return fail("ran out of time; cut " + gained + " of " + count + " " + matcher.label());
        }
        if (current != null) {
            TaskStatus status = current.tick(c);
            if (status == TaskStatus.RUNNING) {
                return TaskStatus.RUNNING;
            }
            int attemptYield = Math.max(0, counted(c) - before);
            gained += attemptYield;
            summary = current.resultSummary();
            BlockPos selectedBase = current instanceof FellTreeSearchTask search ? search.selectedBase() : null;
            boolean selectedTree = selectedBase != null;
            if (selectedTree) {
                selectedBase = selectedBase.immutable();
                trees++;
                if (failedBases.size() < DEFAULT_MAX_TREES) failedBases.add(selectedBase);
                if (attempts.size() < DEFAULT_MAX_TREES) {
                    attempts.add(new TreeAttempt(selectedBase, status, attemptYield, clipReason(summary)));
                }
                if (attemptYield == 0) {
                    emptyTrees++;
                    consecutiveEmptyTrees++;
                } else {
                    consecutiveEmptyTrees = 0;
                }
            } else {
                consecutiveEmptyTrees = 0;
            }
            safeStop(current, c);
            current = null;
            if (gained >= count) {
                return succeed();
            }
            if (fallbackUsed) {
                return fail("cut " + gained + " of " + count + " " + matcher.label() + ": " + summary);
            }
            if (!selectedTree || consecutiveEmptyTrees >= DEFAULT_MAX_EMPTY_TREES) {
                return startFallback(c);
            }
        }
        if (trees >= DEFAULT_MAX_TREES) {
            return startFallback(c);
        }
        before = counted(c);
        current = new FellTreeSearchTask(
                matcher, origin, radius, 0, collect, true, Math.max(1, count - gained), failedBases);
        current.start(c);
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
        String text = fallbackUsed
                ? "gathered " + gained + " " + matcher.label() + " logs (" + felled + " felled, " + (gained - felled)
                        + " mined)"
                : "felled " + gained + " " + matcher.label() + " logs from " + trees
                        + (trees == 1 ? " tree" : " trees");
        summary = withAttemptCauses(text);
        return TaskStatus.SUCCESS;
    }

    private TaskStatus fail(String reason) {
        summary = withAttemptCauses(reason);
        return TaskStatus.FAILED;
    }

    /** Names at most three zero-yield attempts while retaining the total bounded failure count. */
    private String withAttemptCauses(String text) {
        if (emptyTrees == 0) return text;
        StringBuilder result = new StringBuilder(text).append("; tree attempts:");
        int shown = 0;
        for (TreeAttempt attempt : attempts) {
            if (attempt.yielded() != 0) continue;
            if (shown == 3) break;
            shown++;
            result.append(" tree at ")
                    .append(coords(attempt.base()))
                    .append(": ")
                    .append(attempt.reason());
        }
        int omitted = emptyTrees - shown;
        if (omitted > 0) result.append("; and ").append(omitted).append(" more");
        return result.toString();
    }

    private static String clipReason(String reason) {
        String text = reason == null || reason.isBlank() ? "no reason reported" : reason.trim();
        return text.length() <= MAX_ATTEMPT_REASON ? text : text.substring(0, MAX_ATTEMPT_REASON - 3) + "...";
    }

    private static String coords(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
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
        StringBuilder text = new StringBuilder("trees=")
                .append(trees)
                .append('/')
                .append(DEFAULT_MAX_TREES)
                .append(" gained=")
                .append(gained)
                .append('/')
                .append(count)
                .append(" failed=")
                .append(emptyTrees);
        if (!attempts.isEmpty()) {
            TreeAttempt last = attempts.getLast();
            text.append(" lastTree=")
                    .append(coords(last.base()))
                    .append('/')
                    .append(last.outcome())
                    .append("/yield=")
                    .append(last.yielded())
                    .append("/reason=")
                    .append(last.reason());
        }
        if (current != null) text.append(" | ").append(current.debugStatus());
        return text.toString();
    }

    @Override
    public String resultSummary() {
        return summary;
    }

    @Override
    public Optional<TaskWorkResult> workResult() {
        if (!workStarted || itemMatcher.isEmpty()) return Optional.empty();
        if (workResult == null) workResult = createWorkResult(TaskStatus.FAILED);
        return Optional.of(workResult);
    }

    private TaskWorkResult createWorkResult(TaskStatus status) {
        String reasonCode = workVerified >= count ? "complete" : workVerified > 0 ? "partial" : "blocked";
        String blocker = status == TaskStatus.SUCCESS ? "" : summary;
        String nextAction = status == TaskStatus.SUCCESS
                ? "continue with the next order step"
                : "try another valid source for the remaining logs";
        return new TaskWorkResult(
                java.util.List.of(new TaskWorkResult.Line(
                        itemMatcher.orElseThrow().label(), count, workVerified, TaskWorkResult.Evidence.CARRIED)),
                reasonCode,
                blocker,
                nextAction);
    }

    private record TreeAttempt(BlockPos base, TaskStatus outcome, int yielded, String reason) {}
}
