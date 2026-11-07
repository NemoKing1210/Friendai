package dev.friendai.features.acquisition.minecraft.task;

import dev.friendai.debug.DebugTarget;
import dev.friendai.entity.CompanionEntity;
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
import dev.friendai.features.crafting.RecipeBook;
import dev.friendai.features.crafting.Station;
import dev.friendai.features.crafting.minecraft.RecipeIndex;
import dev.friendai.features.crafting.minecraft.Stations;
import dev.friendai.features.creative.CreativeAction;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.inventory.ItemMatcher;
import java.util.ArrayList;
import java.util.EnumSet;
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
    private final Set<String> failedKeys = new LinkedHashSet<>();
    /** Stations found impossible here; the material plan is re-run avoiding each one. */
    private final Set<Station> blockedStations = EnumSet.noneOf(Station.class);

    private Task current;
    private ChildKind childKind = ChildKind.NONE;
    private String childKey = "";
    private String childLabel = "";
    private String childTarget = "";
    private String lastGatherSummary = "";
    private String summary = "";
    private int children;
    private int ticks;

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
        blockedStations.clear();
        children = 0;
        ticks = 0;
    }

    @Override
    public TaskStatus tick(TaskContext c) {
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
        List<CraftPlanner.RawNeed> demand =
                CraftPlanner.materialsFor(planningWindow(remaining), inventory, book, stations, blockedStations);
        while (!demand.isEmpty()) {
            CraftPlanner.RawNeed need = nextNeed(demand);
            if (need.stationNeed()) {
                if (blockedStations.contains(need.station())) {
                    // The plan that avoided this station still needs it: nothing else can make the target.
                    return fail("station " + stationId(book, need) + " is required and cannot be made here");
                }
                String station = chooseObtainable(level, companion, inventory, need);
                if (station == null) {
                    // The station is neither nearby, carried nor craftable from anything reachable here. Plan a
                    // route that avoids it rather than sending the companion off after an item it cannot make.
                    blockedStations.add(need.station());
                    demand = CraftPlanner.materialsFor(
                            planningWindow(remaining), inventory, book, stations, blockedStations);
                    continue;
                }
                return gather(c, companion, need, station, remaining.get(0).item());
            }
            String candidate = choose(level, companion, inventory, need);
            if (candidate == null) {
                String text = "couldn't get " + need.count() + " " + need.label() + " for "
                        + remaining.get(0).item();
                if (!lastGatherSummary.isBlank()) {
                    text += ": " + lastGatherSummary;
                }
                return fail(text);
            }
            return gather(c, companion, need, candidate, remaining.get(0).item());
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

    /**
     * The first candidate that can actually be obtained here, otherwise the first one nobody has failed yet: an
     * unobtainable plan is still tried once, because its failure text is what the owner then reads.
     */
    private String choose(
            ServerLevel level, CompanionEntity companion, Map<String, Integer> inventory, CraftPlanner.RawNeed need) {
        return choose(level, companion, inventory, need, true);
    }

    /**
     * The first candidate with an actionable plan here, never a hopeful fallback: a station that cannot be made has
     * to be recognized as impossible instead of starting a gather child that is bound to fail.
     */
    private String chooseObtainable(
            ServerLevel level, CompanionEntity companion, Map<String, Integer> inventory, CraftPlanner.RawNeed need) {
        return choose(level, companion, inventory, need, false);
    }

    private String choose(
            ServerLevel level,
            CompanionEntity companion,
            Map<String, Integer> inventory,
            CraftPlanner.RawNeed need,
            boolean fallbackToFirst) {
        List<String> candidates = candidates(need);
        if (candidates.isEmpty()) {
            return null;
        }
        WorldFacts facts = WorldScan.scan(level, companion, WorldScan.BLOCK_RADIUS, useContainers, explore);
        RecipeIndex book = RecipeIndex.of(level);
        DropIndex drops = DropIndex.of(level);
        for (String candidate : candidates) {
            ObtainPlan plan =
                    ObtainPlanner.plan(candidate, need.count(), inventory, book, drops, facts, tradingContext);
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

    /**
     * A raw item before a station: gathering material is always progress, while a station may be impossible to make
     * where the companion stands.
     */
    static CraftPlanner.RawNeed nextNeed(List<CraftPlanner.RawNeed> demand) {
        for (CraftPlanner.RawNeed need : demand) {
            if (!need.stationNeed()) {
                return need;
            }
        }
        return demand.get(0);
    }

    /** How the failure names a station: its item id when the book knows one, its own label otherwise. */
    private static String stationId(RecipeBook book, CraftPlanner.RawNeed need) {
        Station station = need.station();
        String id = station == null ? null : book.stationItem(station);
        return id == null || id.isBlank() ? need.label() : id;
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
}
