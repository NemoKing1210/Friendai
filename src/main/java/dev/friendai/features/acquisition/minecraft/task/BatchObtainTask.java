package dev.friendai.features.acquisition.minecraft.task;

import dev.friendai.debug.DebugTarget;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.TaskWorkResult;
import dev.friendai.execution.tool.Caller;
import dev.friendai.features.acquisition.ObtainPlan;
import dev.friendai.features.acquisition.ObtainPlanner;
import dev.friendai.features.acquisition.ObtainStep;
import dev.friendai.features.acquisition.TradingContext;
import dev.friendai.features.acquisition.WorldFacts;
import dev.friendai.features.acquisition.minecraft.ObtainSubtaskFactory;
import dev.friendai.features.acquisition.minecraft.search.DropIndex;
import dev.friendai.features.acquisition.minecraft.search.WorldScan;
import dev.friendai.features.crafting.CraftPlanner;
import dev.friendai.features.crafting.Station;
import dev.friendai.features.crafting.StationFallback;
import dev.friendai.features.crafting.minecraft.RecipeIndex;
import dev.friendai.features.crafting.minecraft.Stations;
import dev.friendai.features.creative.CreativeAction;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.inventory.ItemMatcher;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;

/**
 * Gets a set of finished items by gathering the raw material the whole set needs before crafting any of it. The batch
 * proves its targets against the bag, plans one shared material demand, satisfies the first missing raw item with an
 * ordinary {@link ObtainTask}, and only when nothing raw is missing starts a craft child for a target. A finished plan's
 * summary names the shortfall that stopped it, so a caller learns what is still needed instead of re-asking.
 *
 * <p>The batch owns no world logic of its own: every step is one of the existing acquisition tasks, and every limit it
 * enforces (children, ticks) is counted here rather than in the runner, which is why it is exempt from the task timeout.
 */
public final class BatchObtainTask implements Task {
    /** A batch that spends more children than this is not making progress. */
    public static final int DEFAULT_MAX_CHILDREN = 64;

    public static final int DEFAULT_MAX_TICKS = 24_000;
    public static final int DEFAULT_EXPLORE_RADIUS = 64;
    public static final int DEFAULT_EXPLORE_BLOCKS = 128;
    public static final int DEFAULT_EXPLORE_TICKS = 6_000;

    /** How many raw-item candidates one need is tried against before the batch gives up on it. */
    private static final int MAX_CANDIDATES = 3;

    private enum ChildKind {
        NONE,
        GATHER,
        CRAFT
    }

    private final List<CraftPlanner.Target> targets;
    private final boolean useContainers;
    private final boolean explore;
    private final int maxChildren;
    private final int maxTicks;
    private final int exploreRadius;
    private final int exploreBlocks;
    private final int exploreTicks;
    private final String searchScope;
    private final Caller caller;
    private final TradingContext tradingContext;
    private final ObtainSubtaskFactory subtaskFactory;
    private final ItemMatcher[] workMatchers;
    private final int[] workVerified;
    private final Set<String> failedKeys = new LinkedHashSet<>();

    private Task current;
    private ChildKind childKind = ChildKind.NONE;
    private String childKey = "";
    private String childLabel = "";
    private String childTarget = "";
    private String lastGatherSummary = "";
    private String summary = "";
    private int children;
    private int ticks;
    private boolean workStarted;
    private String workReasonCode = "blocked";
    private String workBlocker = "work stopped before the target was met";
    private String workNextAction = "retry the remaining items after addressing the blocker";
    private TaskWorkResult workResult;

    public BatchObtainTask(
            List<CraftPlanner.Target> targets,
            boolean useContainers,
            boolean explore,
            ObtainSubtaskFactory subtaskFactory) {
        this(
                targets,
                useContainers,
                explore,
                DEFAULT_MAX_CHILDREN,
                DEFAULT_MAX_TICKS,
                DEFAULT_EXPLORE_RADIUS,
                DEFAULT_EXPLORE_BLOCKS,
                DEFAULT_EXPLORE_TICKS,
                "local",
                null,
                null,
                subtaskFactory);
    }

    /** The full constructor, for tests with tighter limits. */
    public BatchObtainTask(
            List<CraftPlanner.Target> targets,
            boolean useContainers,
            boolean explore,
            int maxChildren,
            int maxTicks,
            int exploreRadius,
            int exploreBlocks,
            int exploreTicks,
            String searchScope,
            Caller caller,
            TradingContext tradingContext,
            ObtainSubtaskFactory subtaskFactory) {
        if (targets == null || targets.isEmpty()) {
            throw new IllegalArgumentException("a batch needs at least one target");
        }
        this.targets = List.copyOf(targets);
        int workTargetCount = Math.min(this.targets.size(), CraftPlanner.MAX_TARGETS);
        this.workMatchers = new ItemMatcher[workTargetCount];
        this.workVerified = new int[workTargetCount];
        for (int i = 0; i < workTargetCount; i++) {
            workMatchers[i] = ItemMatcher.parse(this.targets.get(i).item()).orElse(null);
        }
        this.useContainers = useContainers;
        this.explore = explore;
        this.maxChildren = Math.max(1, maxChildren);
        this.maxTicks = Math.max(1, maxTicks);
        this.exploreRadius = exploreRadius;
        this.exploreBlocks = exploreBlocks;
        this.exploreTicks = exploreTicks;
        this.searchScope = searchScope == null ? "local" : searchScope;
        this.caller = caller;
        this.tradingContext = tradingContext;
        this.subtaskFactory = subtaskFactory == null ? ObtainSubtaskFactory.UNAVAILABLE : subtaskFactory;
    }

    @Override
    public String type() {
        return "obtain_items";
    }

    /** Every target the batch still lacks is material a pillar or bridge must not spend. */
    @Override
    public Map<String, Integer> materialNeeds(TaskContext context) {
        Map<String, Integer> needs = new LinkedHashMap<>();
        for (CraftPlanner.Target target : targets) {
            int missing = target.count() - AcquiredItems.have(context.companion(), target.item());
            if (missing <= 0) {
                continue;
            }
            String id = ItemMatcher.parse(target.item()).map(ItemMatcher::label).orElse("");
            if (!id.isBlank()) {
                needs.merge(id, missing, Integer::sum);
            }
        }
        return needs.isEmpty() ? Map.of() : Map.copyOf(needs);
    }

    @Override
    public boolean hasTimeout() {
        return false;
    }

    @Override
    public void start(TaskContext c) {
        current = null;
        childKind = ChildKind.NONE;
        childKey = "";
        childLabel = "";
        childTarget = "";
        lastGatherSummary = "";
        summary = "";
        failedKeys.clear();
        children = 0;
        ticks = 0;
        workStarted = true;
        workReasonCode = "blocked";
        workBlocker = "work stopped before the target was met";
        workNextAction = "retry the remaining items after addressing the blocker";
        workResult = null;
        Arrays.fill(workVerified, 0);
        captureWorkCounts(c.companion());
    }

    @Override
    public TaskStatus tick(TaskContext c) {
        TaskStatus status = tickWork(c);
        captureWorkCounts(c.companion());
        if (status != TaskStatus.RUNNING) {
            workReasonCode = status == TaskStatus.SUCCESS ? "complete" : hasVerifiedWork() ? "partial" : "blocked";
            workBlocker = status == TaskStatus.SUCCESS ? "" : summary;
            workNextAction = status == TaskStatus.SUCCESS
                    ? "continue with the next order step"
                    : "retry or choose another source for the remaining items";
            workResult = createWorkResult();
        }
        return status;
    }

    private TaskStatus tickWork(TaskContext c) {
        if (++ticks > maxTicks) {
            return fail("ran out of time; " + progress(c.companion()));
        }
        if (current != null) {
            TaskStatus status = current.tick(c);
            if (status == TaskStatus.RUNNING) {
                return TaskStatus.RUNNING;
            }
            String subSummary = current.resultSummary();
            ChildKind kind = childKind;
            String key = childKey;
            String target = childTarget;
            ObtainTask.safeStop(current, c);
            current = null;
            childKind = ChildKind.NONE;
            childKey = "";
            childLabel = "";
            childTarget = "";
            if (kind == ChildKind.CRAFT && status != TaskStatus.SUCCESS) {
                return fail("couldn't craft " + target + ": " + subSummary);
            }
            if (kind == ChildKind.GATHER && status != TaskStatus.SUCCESS) {
                failedKeys.add(key);
                lastGatherSummary = subSummary;
            }
        }
        return plan(c);
    }

    private TaskStatus plan(TaskContext c) {
        CompanionEntity companion = c.companion();
        List<CraftPlanner.Target> remaining = remaining(companion);
        if (remaining.isEmpty()) {
            return succeed();
        }
        if (children >= maxChildren) {
            return fail("gave up after " + maxChildren + " steps; " + progress(companion));
        }
        if (c.canUseCreativeAction(CreativeAction.CREATE_ITEM)) {
            CraftPlanner.Target target = remaining.get(0);
            return startChild(
                    c, obtain(target.item(), target.count()), ChildKind.GATHER, "", target.item(), target.item());
        }
        ServerLevel level = c.level();
        Map<String, Integer> inventory = AcquiredItems.inventoryMap(companion);
        RecipeIndex book = RecipeIndex.of(level);
        Set<Station> stations = Stations.available(level, companion);
        List<CraftPlanner.Target> window = planningWindow(remaining);
        List<CraftPlanner.RawNeed> demand = CraftPlanner.materialsFor(window, inventory, book, stations);
        if (!demand.isEmpty()) {
            // One scan answers every station this pass rejects; a later pass scans again, so a station tried and
            // skipped here is not excluded for the rest of the task.
            Search search = new Search(
                    WorldScan.scan(level, companion, WorldScan.BLOCK_RADIUS, useContainers, explore),
                    book,
                    DropIndex.of(level));
            StationFallback.Decision decision = StationFallback.decide(
                    window, demand, inventory, book, stations, need -> choose(inventory, search, need, false));
            if (decision instanceof StationFallback.Blocked blocked) {
                String text = "station " + blocked.stationId() + " is required and cannot be made here; "
                        + progress(companion);
                if (!lastGatherSummary.isBlank()) {
                    text += ": " + lastGatherSummary;
                }
                return fail(text);
            }
            if (decision instanceof StationFallback.Next next) {
                String candidate = next.candidate();
                if (candidate == null) {
                    candidate = choose(inventory, search, next.need(), true);
                    if (candidate == null) {
                        String text = "couldn't get " + next.need().count() + " "
                                + next.need().label() + " for "
                                + remaining.get(0).item();
                        if (!lastGatherSummary.isBlank()) {
                            text += ": " + lastGatherSummary;
                        }
                        return fail(text);
                    }
                }
                return gather(
                        c, companion, next.need(), candidate, remaining.get(0).item());
            }
        }
        CraftPlanner.Target target = remaining.get(0);
        int missing = target.count() - AcquiredItems.have(companion, target.item());
        ObtainSubtaskFactory.Created created = subtaskFactory.create(
                c,
                new ObtainStep.Craft(target.item(), missing),
                new ObtainSubtaskFactory.Options(
                        searchScope, caller, tradingContext, exploreRadius, exploreBlocks, exploreTicks));
        return startChild(c, created.task(), ChildKind.CRAFT, "", target.item(), target.item());
    }

    private ObtainTask obtain(String item, int count) {
        return new ObtainTask(
                item,
                count,
                useContainers,
                explore,
                ObtainTask.DEFAULT_MAX_STEPS,
                maxTicks,
                exploreRadius,
                exploreBlocks,
                exploreTicks,
                searchScope,
                caller,
                tradingContext,
                subtaskFactory);
    }

    /** The world view one planning pass asks about, scanned once however many stations the pass rejects. */
    private record Search(WorldFacts facts, RecipeIndex book, DropIndex drops) {}

    /**
     * The first candidate that can actually be obtained here, otherwise, when {@code fallbackToFirst} is set, the
     * first one nobody has failed yet: an unobtainable plan is still tried once, because its failure text is what the
     * owner then reads. A station is looked up with the fallback off, so an impossible one is recognized instead of
     * starting a gather child that is bound to fail.
     */
    private String choose(
            Map<String, Integer> inventory, Search search, CraftPlanner.RawNeed need, boolean fallbackToFirst) {
        List<String> candidates = candidates(need);
        if (candidates.isEmpty()) {
            return null;
        }
        for (String candidate : candidates) {
            ObtainPlan plan = ObtainPlanner.plan(
                    candidate, need.count(), inventory, search.book(), search.drops(), search.facts(), tradingContext);
            if (plan.actionable()) {
                return candidate;
            }
        }
        return fallbackToFirst ? candidates.get(0) : null;
    }

    /** The need's candidates nobody has failed yet, in preference order. */
    private List<String> candidates(CraftPlanner.RawNeed need) {
        List<String> candidates = new ArrayList<>();
        for (String candidate : need.candidates()) {
            if (candidate == null || candidate.isBlank() || candidates.size() >= MAX_CANDIDATES) {
                continue;
            }
            if (!failedKeys.contains(need.label() + "|" + candidate)) {
                candidates.add(candidate);
            }
        }
        return candidates;
    }

    private TaskStatus gather(
            TaskContext c, CompanionEntity companion, CraftPlanner.RawNeed need, String candidate, String target) {
        // The need is what the shared material plan is still short of; the child asks for that plus what the bag
        // already holds, because an ObtainTask succeeds once the total it was sent for is in the inventory.
        int wanted = need.count() + AcquiredItems.have(companion, candidate);
        return startChild(
                c, obtain(candidate, wanted), ChildKind.GATHER, need.label() + "|" + candidate, need.label(), target);
    }

    /** The craft planner accepts at most eight targets per demand; the rest are planned on the next tick. */
    private static List<CraftPlanner.Target> planningWindow(List<CraftPlanner.Target> remaining) {
        return remaining.size() <= CraftPlanner.MAX_TARGETS
                ? remaining
                : remaining.subList(0, CraftPlanner.MAX_TARGETS);
    }

    private List<CraftPlanner.Target> remaining(CompanionEntity companion) {
        List<CraftPlanner.Target> remaining = new ArrayList<>();
        for (CraftPlanner.Target target : targets) {
            if (AcquiredItems.have(companion, target.item()) < target.count()) {
                remaining.add(target);
            }
        }
        return remaining;
    }

    private TaskStatus startChild(TaskContext c, Task task, ChildKind kind, String key, String label, String target) {
        current = task;
        childKind = kind;
        childKey = key;
        childLabel = label;
        childTarget = target;
        children++;
        current.start(c);
        return TaskStatus.RUNNING;
    }

    private TaskStatus succeed() {
        StringBuilder text = new StringBuilder("got ");
        for (int i = 0; i < targets.size(); i++) {
            if (i > 0) {
                text.append(", ");
            }
            CraftPlanner.Target target = targets.get(i);
            text.append(target.count()).append(' ').append(target.item());
        }
        text.append(" in ").append(children).append(" steps");
        summary = text.toString();
        return TaskStatus.SUCCESS;
    }

    private TaskStatus fail(String text) {
        summary = text;
        return TaskStatus.FAILED;
    }

    private String progress(CompanionEntity companion) {
        StringBuilder text = new StringBuilder("have ");
        for (int i = 0; i < targets.size(); i++) {
            if (i > 0) {
                text.append(", ");
            }
            CraftPlanner.Target target = targets.get(i);
            text.append(Math.min(target.count(), AcquiredItems.have(companion, target.item())))
                    .append('/')
                    .append(target.count())
                    .append(' ')
                    .append(target.item());
        }
        return text.toString();
    }

    private String targetsText() {
        StringBuilder text = new StringBuilder();
        for (CraftPlanner.Target target : targets) {
            if (!text.isEmpty()) {
                text.append(", ");
            }
            text.append(target.count()).append(' ').append(target.item());
        }
        return text.toString();
    }

    @Override
    public void stop(TaskContext c) {
        if (current != null) {
            ObtainTask.safeStop(current, c);
            current = null;
        }
        childKind = ChildKind.NONE;
        c.companion().getNavigation().stop();
    }

    @Override
    public void onTerminal(TaskContext c) {
        current = null;
    }

    @Override
    public String describe() {
        return switch (childKind) {
            case GATHER -> "gathering " + childLabel + " for " + targetsText();
            case CRAFT -> "crafting " + childTarget;
            case NONE -> "planning " + targetsText();
        };
    }

    @Override
    public String debugStatus() {
        String inner = current == null ? "" : current.debugStatus();
        return "t=" + ticks + "/" + maxTicks + " children=" + children + "/" + maxChildren
                + (inner.isEmpty() ? "" : " | " + inner);
    }

    @Override
    public List<DebugTarget> debugTargets() {
        return current == null ? List.of() : current.debugTargets();
    }

    @Override
    public String resultSummary() {
        return summary;
    }

    @Override
    public java.util.Optional<TaskWorkResult> workResult() {
        if (!workStarted || workMatchers.length == 0) return java.util.Optional.empty();
        if (workResult == null) workResult = createWorkResult();
        return java.util.Optional.of(workResult);
    }

    private void captureWorkCounts(CompanionEntity companion) {
        Arrays.fill(workVerified, 0);
        for (int slot = 0; slot < companion.inventory().getContainerSize(); slot++) {
            var stack = companion.inventory().getItem(slot);
            if (stack.isEmpty()) continue;
            for (int i = 0; i < workMatchers.length; i++) {
                ItemMatcher matcher = workMatchers[i];
                if (matcher != null && matcher.test(stack)) workVerified[i] += stack.getCount();
            }
        }
        var held = companion.getMainHandItem();
        if (held.isEmpty()) return;
        for (int i = 0; i < workMatchers.length; i++) {
            ItemMatcher matcher = workMatchers[i];
            if (matcher != null && matcher.test(held)) workVerified[i] += held.getCount();
        }
    }

    private boolean hasVerifiedWork() {
        for (int verified : workVerified) if (verified > 0) return true;
        return false;
    }

    private TaskWorkResult createWorkResult() {
        List<TaskWorkResult.Line> lines = new ArrayList<>(workMatchers.length);
        for (int i = 0; i < workMatchers.length; i++) {
            CraftPlanner.Target target = targets.get(i);
            boolean alreadyReported = false;
            for (int previous = 0; previous < i; previous++) {
                if (targets.get(previous).item().equals(target.item())) {
                    alreadyReported = true;
                    break;
                }
            }
            if (alreadyReported) continue;
            int requested = 0;
            for (int later = i; later < workMatchers.length; later++) {
                if (targets.get(later).item().equals(target.item()))
                    requested += targets.get(later).count();
            }
            lines.add(new TaskWorkResult.Line(
                    target.item(), requested, workVerified[i], TaskWorkResult.Evidence.CARRIED));
        }
        String blocker = workBlocker == null ? "" : workBlocker;
        return new TaskWorkResult(lines, workReasonCode, blocker, workNextAction);
    }
}
