package dev.friendai.features.mining.minecraft.task;

import dev.friendai.execution.TaskWorkResult;
import dev.friendai.features.mining.FellAttempts;
import dev.friendai.foundation.diagnostics.LogEvents;
import dev.friendai.task.ProgressWatch;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.inventory.InventoryOps;
import dev.friendai.task.inventory.ItemMatcher;
import dev.friendai.world.block.BlockMatcher;
import java.util.ArrayDeque;
import java.util.LinkedHashSet;
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
    /**
     * How many trees in a row may give nothing before the count miner takes over. A refusal (too tall for the
     * scaffold, no scaffold material, a protected trunk) is evidence about the trees around here, not about the
     * order: two in a row is enough to stop walking to more of them and let the existing miner take the logs it can
     * reach from the ground.
     */
    public static final int DEFAULT_MAX_EMPTY_FELLS = 2;
    /** How long one request may run before it reports what it gathered, in ticks. */
    public static final int DEFAULT_MAX_TICKS = 24_000;
    /** How many finished attempts one report names, and how long one reason may be. */
    private static final int MAX_ATTEMPT_NOTES = 3;

    private static final int MAX_NOTE_CHARS = 90;

    private final BlockMatcher matcher;
    private final int count;
    private final int radius;
    private final boolean collect;
    private final Set<BlockPos> failedBases = new LinkedHashSet<>();

    private BlockPos origin;
    private Optional<ItemMatcher> itemMatcher = Optional.empty();
    private Task current;
    private int trees;
    /** Attempts in a row that added nothing; a productive tree resets it. */
    private int emptyFells;
    /** Bounded short reasons for the last attempts, so a fallback or a failure says why the trees gave nothing. */
    private final ArrayDeque<String> attemptNotes = new ArrayDeque<>();

    private int gained;
    /** Verified effects only: logs actually collected plus a running child's own measured stamp. */
    private long verifiedProgress;

    private long lastChildStamp = ProgressWatch.UNKNOWN;
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
        emptyFells = 0;
        attemptNotes.clear();
        failedBases.clear();
        gained = 0;
        verifiedProgress = 0L;
        lastChildStamp = ProgressWatch.UNKNOWN;
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
        observeChildProgress();
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
            int yielded = Math.max(0, counted(c) - before);
            gained += yielded;
            verifiedProgress += yielded;
            summary = current.resultSummary();
            boolean selectedTree = false;
            if (current instanceof FellTreeSearchTask search && search.selectedBase() != null) {
                selectedTree = true;
                trees++;
                if (failedBases.size() < DEFAULT_MAX_TREES) failedBases.add(search.selectedBase());
            }
            if (selectedTree) {
                emptyFells = FellAttempts.nextEmptyStreak(emptyFells, yielded);
                if (yielded == 0) note("(" + trees + ") " + summary);
                recordAttempt(c, yielded, status);
            }
            safeStop(current, c);
            current = null;
            if (gained >= count) {
                return succeed();
            }
            if (fallbackUsed) {
                return fail("cut " + gained + " of " + count + " " + matcher.label() + ": " + summary);
            }
            if (!selectedTree) {
                return startFallback(c);
            }
            if (FellAttempts.fallBack(trees, emptyFells, DEFAULT_MAX_TREES, DEFAULT_MAX_EMPTY_FELLS)) {
                // Two trees in a row gave nothing (or the whole tree budget is spent): the trees near here are not
                // fellable for this companion, so the count miner reaches the same logs from the ground instead of
                // walking to another refusal.
                return startFallback(c);
            }
        }
        before = counted(c);
        current = new FellTreeSearchTask(
                matcher, origin, radius, 0, collect, true, Math.max(1, count - gained), failedBases);
        current.start(c);
        lastChildStamp = current.progressStamp();
        return TaskStatus.RUNNING;
    }

    /**
     * One bounded evidence record per finished tree attempt: the attempt number, the outcome and how many logs it
     * added, with the child's own short reason. No coordinates and no unbounded prose, so a run's log says why each
     * tree failed instead of only how many were tried.
     */
    private void recordAttempt(TaskContext c, int yielded, TaskStatus status) {
        Map<String, String> facts = new java.util.LinkedHashMap<>();
        facts.put("tree", Integer.toString(trees));
        facts.put("yielded", Integer.toString(yielded));
        facts.put("outcome", status.name().toLowerCase(java.util.Locale.ROOT));
        facts.put("target", matcher.label());
        String reason = shorten(summary);
        if (!reason.isBlank()) facts.put("reason", reason);
        c.decision(LogEvents.RESOURCE_DECISION, "tree_attempt", facts);
    }

    private void note(String text) {
        attemptNotes.addLast(shorten(text));
        while (attemptNotes.size() > MAX_ATTEMPT_NOTES) {
            attemptNotes.removeFirst();
        }
    }

    /** The attempts in one bounded phrase, so the fallback and the failure say what the trees said. */
    private String notesText() {
        return attemptNotes.isEmpty() ? "" : "; tried " + String.join("; ", attemptNotes);
    }

    private static String shorten(String text) {
        if (text == null) return "";
        String flat = text.replace('\n', ' ').strip();
        return flat.length() <= MAX_NOTE_CHARS ? flat : flat.substring(0, MAX_NOTE_CHARS - 1) + "…";
    }

    private TaskStatus startFallback(TaskContext c) {
        if (fallbackUsed) {
            return fail("cut " + gained + " of " + count + " " + matcher.label() + ": " + summary + notesText());
        }
        fallbackUsed = true;
        felled = gained;
        before = counted(c);
        current = new MineBlocksTask(matcher, Math.max(1, count - gained), radius, origin, collect);
        current.start(c);
        lastChildStamp = current.progressStamp();
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

    /**
     * Logs the companion actually collected, plus a running child's own verified stamp. A tree attempt that yielded
     * nothing is an attempt, not progress, so it must not keep the order looking alive.
     */
    private void observeChildProgress() {
        if (current == null) return;
        long child = current.progressStamp();
        if (child != ProgressWatch.UNKNOWN && child != lastChildStamp) {
            lastChildStamp = child;
            verifiedProgress++;
        }
    }

    @Override
    public long progressStamp() {
        return verifiedProgress;
    }

    @Override
    public int waitingTicks() {
        return current == null ? -1 : current.waitingTicks();
    }

    @Override
    public int waitingLimitTicks() {
        return current == null || current.waitingTicks() < 0 ? 0 : current.waitingLimitTicks();
    }

    @Override
    public String describe() {
        return current == null ? "looking for a " + matcher.label() + " tree" : current.describe();
    }

    @Override
    public String debugStatus() {
        return "mode=" + (fallbackUsed ? "count-mine" : "fell") + " trees=" + trees + "/" + DEFAULT_MAX_TREES
                + " empty=" + emptyFells + "/" + DEFAULT_MAX_EMPTY_FELLS
                + " gained=" + gained + "/" + count
                + (current == null ? "" : " | " + current.debugStatus());
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
}
