package dev.friendai.features.acquisition.minecraft.task;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.friendai.debug.DebugTarget;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.TaskWorkResult;
import dev.friendai.execution.tool.Caller;
import dev.friendai.features.acquisition.AcquisitionAttempts;
import dev.friendai.features.acquisition.NeedAllocation;
import dev.friendai.features.acquisition.ObtainPlan;
import dev.friendai.features.acquisition.ObtainPlanner;
import dev.friendai.features.acquisition.ObtainStep;
import dev.friendai.features.acquisition.ObtainText;
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
import dev.friendai.task.ProgressWatch;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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
    public static final String TYPE = "obtain_items";

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
    /** Shared with every gather child, so one failed material source is not retried by the next child. */
    private final AcquisitionAttempts attempts = new AcquisitionAttempts();
    /** The last plan that stopped at the planner's work limit, so a failure names the limit, not a missing item. */
    private ObtainPlan limitedPlan;
    /** Planning passes and the plans a later question of the same pass could reuse instead of searching again. */
    private int planPasses;

    private int planReuses;

    private Task current;
    /** A child cancellation already stopped, whose terminal effects are still owed if the runner settles us after. */
    private Task stoppedChild;

    private ChildKind childKind = ChildKind.NONE;
    private String childKey = "";
    private String childLabel = "";
    private String childTarget = "";
    private String lastGatherSummary = "";
    private String summary = "";
    private int children;
    private int ticks;
    /**
     * Verified effects the whole order has seen: a target count that grew, a finished child, or a running child's own
     * verified stamp. A child that keeps talking without moving any of them is not progress.
     */
    private long verifiedProgress;
    /** The carried count of every target at the last observation, so only a pickup advances the stamp. */
    private final int[] lastVerified;

    private long lastChildStamp = ProgressWatch.UNKNOWN;
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
        this.lastVerified = new int[workTargetCount];
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
        return TYPE;
    }

    /** The phase of the batch now, so a reload knows whether a search was running instead of guessing. */
    public Phase phase() {
        if (current instanceof ObtainTask obtain && obtain.phase() == ObtainTask.Phase.NESTED_SEARCH) {
            return Phase.NESTED_SEARCH;
        }
        return switch (childKind) {
            case NONE -> Phase.PLANNING;
            case GATHER -> Phase.GATHER;
            case CRAFT -> Phase.CRAFT;
        };
    }

    /** Where one batch was when it was saved. */
    public enum Phase {
        PLANNING,
        GATHER,
        CRAFT,
        NESTED_SEARCH
    }

    /**
     * Saves the batch's own boundary, never its child's: the child is one step, and its checkpoint restored alone would
     * report a finished step as a finished batch. The targets and effective options let the reload rebuild the same
     * order from the live bag; a running nested search is refused instead of restarted under a fresh budget.
     */
    @Override
    public String saveData() {
        UUID principal = caller == null ? null : caller.uuid();
        if (principal == null) {
            return null;
        }
        return new Saved(
                        Saved.CURRENT_VERSION,
                        phase(),
                        targets,
                        useContainers,
                        explore,
                        maxChildren,
                        maxTicks,
                        exploreRadius,
                        exploreBlocks,
                        exploreTicks,
                        searchScope,
                        principal)
                .encode();
    }

    /**
     * The persisted boundary of one batch acquisition, bounded and versioned. It carries the effective options the
     * task was built with, so an omitted and an explicit {@code explore} reload the same search reach.
     */
    public record Saved(
            int version,
            Phase phase,
            List<CraftPlanner.Target> targets,
            boolean useContainers,
            boolean explore,
            int maxChildren,
            int maxTicks,
            int exploreRadius,
            int exploreBlocks,
            int exploreTicks,
            String searchScope,
            UUID principal) {
        public static final int CURRENT_VERSION = 1;
        /** The largest descriptor this codec reads or writes; a bigger payload is not a batch boundary. */
        public static final int MAX_CHARS = 4096;

        private static final int MAX_ITEM_CHARS = 128;
        private static final int MAX_SCOPE_CHARS = 32;

        public Saved {
            targets = targets == null ? List.of() : List.copyOf(targets);
            searchScope = searchScope == null || searchScope.isBlank() ? "local" : searchScope.strip();
        }

        public String encode() {
            JsonArray items = new JsonArray();
            for (CraftPlanner.Target target : targets) {
                JsonObject entry = new JsonObject();
                entry.addProperty("item", target.item());
                entry.addProperty("count", target.count());
                items.add(entry);
            }
            JsonObject root = new JsonObject();
            root.addProperty("v", version);
            root.addProperty("phase", phase.name());
            root.add("items", items);
            root.addProperty("containers", useContainers);
            root.addProperty("explore", explore);
            root.addProperty("children", maxChildren);
            root.addProperty("ticks", maxTicks);
            root.addProperty("radius", exploreRadius);
            root.addProperty("blocks", exploreBlocks);
            root.addProperty("searchTicks", exploreTicks);
            root.addProperty("scope", searchScope);
            if (principal != null) root.addProperty("principal", principal.toString());
            String text = root.toString();
            if (text.length() > MAX_CHARS) {
                throw new IllegalStateException("acquisition checkpoint exceeds size limit");
            }
            return text;
        }

        /** @return the boundary, or empty for a payload this version did not write or that is out of range */
        public static Optional<Saved> decode(String data) {
            if (data == null || data.length() > MAX_CHARS) {
                return Optional.empty();
            }
            try {
                JsonObject root = JsonParser.parseString(data).getAsJsonObject();
                int version = root.get("v").getAsInt();
                if (version != CURRENT_VERSION) {
                    return Optional.empty();
                }
                JsonArray items = root.getAsJsonArray("items");
                List<CraftPlanner.Target> targets = new ArrayList<>();
                for (JsonElement element : items) {
                    JsonObject entry = element.getAsJsonObject();
                    targets.add(new CraftPlanner.Target(
                            entry.get("item").getAsString(), entry.get("count").getAsInt()));
                }
                Saved saved = new Saved(
                        version,
                        Phase.valueOf(root.get("phase").getAsString()),
                        targets,
                        root.get("containers").getAsBoolean(),
                        root.get("explore").getAsBoolean(),
                        root.get("children").getAsInt(),
                        root.get("ticks").getAsInt(),
                        root.get("radius").getAsInt(),
                        root.get("blocks").getAsInt(),
                        root.get("searchTicks").getAsInt(),
                        root.get("scope").getAsString(),
                        root.has("principal")
                                ? UUID.fromString(root.get("principal").getAsString())
                                : null);
                return saved.valid() ? Optional.of(saved) : Optional.empty();
            } catch (RuntimeException malformed) {
                return Optional.empty();
            }
        }

        boolean valid() {
            if (targets.isEmpty() || targets.size() > CraftPlanner.MAX_TARGETS) {
                return false;
            }
            for (CraftPlanner.Target target : targets) {
                if (target.item().isBlank() || target.item().length() > MAX_ITEM_CHARS) {
                    return false;
                }
            }
            return maxChildren >= 1
                    && maxTicks >= 1
                    && exploreRadius >= 0
                    && exploreBlocks >= 0
                    && exploreTicks >= 0
                    && searchScope.length() <= MAX_SCOPE_CHARS;
        }
    }

    /** Protects outstanding targets and every recipe ingredient the batch still needs from disposable support. */
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
        if (!context.canUseCreativeAction(CreativeAction.CREATE_ITEM) && context.level() instanceof ServerLevel level) {
            CompanionEntity companion = context.companion();
            List<CraftPlanner.Target> remaining = remaining(companion);
            Map<String, Integer> inventory = AcquiredItems.inventoryMap(companion);
            Map<String, Integer> availableInventory = new LinkedHashMap<>(inventory);
            RecipeIndex book = RecipeIndex.of(level);
            Set<Station> stations = Stations.available(level, companion);
            Search search = null;
            for (int start = 0; start < remaining.size(); start += CraftPlanner.MAX_TARGETS) {
                int end = Math.min(remaining.size(), start + CraftPlanner.MAX_TARGETS);
                List<CraftPlanner.Target> window = remaining.subList(start, end);
                List<CraftPlanner.RawNeed> liveDemand = CraftPlanner.materialsFor(window, inventory, book, stations);
                List<CraftPlanner.RawNeed> fullDemand = CraftPlanner.materialsFor(window, Map.of(), book, stations);
                for (CraftPlanner.RawNeed liveNeed : liveDemand) {
                    CraftPlanner.RawNeed fullNeed = fullDemand.stream()
                            .filter(need -> need.label().equals(liveNeed.label())
                                    && need.candidates().stream().anyMatch(liveNeed.candidates()::contains))
                            .findFirst()
                            .orElse(null);
                    int remainingCount = fullNeed == null ? liveNeed.count() : fullNeed.count();
                    if (fullNeed == null) {
                        // This need belongs to a recipe branch the empty-inventory plan cannot describe. Its count
                        // is already a shortage, so protect carried items without subtracting them from that count.
                        for (String candidate : liveNeed.candidates()) {
                            int carried = availableInventory.getOrDefault(candidate, 0);
                            if (carried > 0) {
                                needs.merge(candidate, carried, Integer::sum);
                                availableInventory.put(candidate, 0);
                            }
                        }
                    } else {
                        for (String candidate : liveNeed.candidates()) {
                            int carried = availableInventory.getOrDefault(candidate, 0);
                            int protectedCount = Math.min(carried, remainingCount);
                            if (protectedCount > 0) {
                                needs.merge(candidate, protectedCount, Integer::sum);
                                availableInventory.put(candidate, carried - protectedCount);
                                remainingCount -= protectedCount;
                            }
                            if (remainingCount == 0) {
                                break;
                            }
                        }
                    }
                    if (remainingCount > 0) {
                        // A running gather has already chosen its item; keep protecting that target until its child
                        // finishes. Otherwise follow the same candidate order and source check as the live batch
                        // plan. Full demand is used only when it describes this same live recipe branch.
                        String candidate = start == 0 ? inFlightGatherCandidate(liveNeed.label()) : null;
                        if (candidate == null) {
                            if (search == null) {
                                search = new Search(
                                        inventory,
                                        WorldScan.scan(level, companion, WorldScan.BLOCK_RADIUS, useContainers, explore)
                                                .withAttemptedSources(attempts.sourceFailures()),
                                        book,
                                        DropIndex.of(level));
                            }
                            List<NeedAllocation.Leg> legs = allocate(search, liveNeed);
                            candidate = legs.isEmpty() ? null : legs.get(0).item();
                        }
                        if (candidate != null) {
                            needs.merge(candidate, remainingCount, Integer::sum);
                        }
                    }
                }
                if (start == 0 && current != null && childKind == ChildKind.GATHER) {
                    String activeLabel = inFlightGatherLabel();
                    if (activeLabel != null
                            && liveDemand.stream()
                                    .noneMatch(need -> need.label().equals(activeLabel))) {
                        current.materialNeeds(context).forEach((item, count) -> needs.merge(item, count, Math::max));
                    }
                }
            }
            if (search != null) {
                planPasses += search.passes();
                planReuses += search.reuses();
            }
        }
        return needs.isEmpty() ? Map.of() : Map.copyOf(needs);
    }

    private String inFlightGatherCandidate(String label) {
        String prefix = label + "|";
        return childKind == ChildKind.GATHER && childKey.startsWith(prefix)
                ? childKey.substring(prefix.length())
                : null;
    }

    private String inFlightGatherLabel() {
        if (childKind != ChildKind.GATHER) {
            return null;
        }
        int separator = childKey.lastIndexOf('|');
        return separator < 0 ? null : childKey.substring(0, separator);
    }

    @Override
    public Map<String, Integer> travelMaterialNeeds(TaskContext context) {
        return current == null ? Map.of() : current.travelMaterialNeeds(context);
    }

    @Override
    public Task cleanupAfterCancel(TaskContext context) {
        return current == null ? null : current.cleanupAfterCancel(context);
    }

    @Override
    public boolean hasTimeout() {
        return false;
    }

    @Override
    public void start(TaskContext c) {
        current = null;
        stoppedChild = null;
        childKind = ChildKind.NONE;
        childKey = "";
        childLabel = "";
        childTarget = "";
        lastGatherSummary = "";
        summary = "";
        failedKeys.clear();
        attempts.clear();
        children = 0;
        ticks = 0;
        workStarted = true;
        workReasonCode = "blocked";
        workBlocker = "work stopped before the target was met";
        workNextAction = "retry the remaining items after addressing the blocker";
        workResult = null;
        Arrays.fill(workVerified, 0);
        captureWorkCounts(c.companion());
        System.arraycopy(workVerified, 0, lastVerified, 0, workVerified.length);
        verifiedProgress = 0L;
        lastChildStamp = ProgressWatch.UNKNOWN;
    }

    @Override
    public TaskStatus tick(TaskContext c) {
        TaskStatus status = tickWork(c);
        captureWorkCounts(c.companion());
        observeVerifiedProgress();
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
            ObtainTask.safeTerminate(current, c);
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
                    inventory,
                    WorldScan.scan(level, companion, WorldScan.BLOCK_RADIUS, useContainers, explore)
                            .withAttemptedSources(attempts.sourceFailures()),
                    book,
                    DropIndex.of(level));
            StationFallback.Decision decision =
                    StationFallback.decide(window, demand, inventory, book, stations, need -> executable(search, need));
            planPasses += search.passes();
            planReuses += search.reuses();
            if (decision instanceof StationFallback.Blocked blocked) {
                String text = "station " + blocked.stationId() + " is required and cannot be made here; "
                        + progress(companion);
                if (!lastGatherSummary.isBlank()) {
                    text += ": " + lastGatherSummary;
                }
                return fail(text);
            }
            if (decision instanceof StationFallback.Next next) {
                List<NeedAllocation.Leg> legs;
                if (next.candidate() != null) {
                    legs = List.of(
                            new NeedAllocation.Leg(next.candidate(), next.need().count()));
                } else {
                    // The family is ranked as for any need; the first leg is gathered now and the next pass
                    // decides the rest from what is then still missing.
                    legs = allocate(search, next.need());
                    if (legs.isEmpty()) {
                        NeedAllocation.Leg fallback = fallback(next.need());
                        legs = fallback == null ? List.of() : List.of(fallback);
                    }
                }
                if (legs.isEmpty()) {
                    String text = limitedPlan != null
                            ? ObtainText.failureText(
                                    limitedPlan,
                                    next.need().label(),
                                    next.need().count(),
                                    !explore)
                            : "couldn't get " + next.need().count() + " "
                                    + next.need().label() + " for "
                                    + remaining.get(0).item();
                    if (!lastGatherSummary.isBlank()) {
                        text += ": " + lastGatherSummary;
                    }
                    return fail(text);
                }
                NeedAllocation.Leg first = legs.get(0);
                // The pass that chose this leg already computed its plan; the child starts from it when nothing
                // around it has changed by the time it looks, instead of searching the same evidence again.
                return gather(
                        c,
                        companion,
                        next.need(),
                        first,
                        remaining.get(0).item(),
                        search.plan(first.item(), first.count()),
                        search.facts);
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
                subtaskFactory,
                attempts);
    }

    /**
     * The world view one planning pass asks about, scanned once however many stations the pass rejects, plus the plans
     * it has already computed. The station check and the allocation that follows it ask about the same candidate,
     * count and evidence, so the second question is answered from the first plan instead of a second search.
     */
    private final class Search {
        private final Map<String, Integer> inventory;
        private final WorldFacts facts;
        private final RecipeIndex book;
        private final DropIndex drops;
        private final Map<String, ObtainPlan> plans = new LinkedHashMap<>();
        private int reuses;

        Search(Map<String, Integer> inventory, WorldFacts facts, RecipeIndex book, DropIndex drops) {
            this.inventory = inventory;
            this.facts = facts;
            this.book = book;
            this.drops = drops;
        }

        ObtainPlan plan(String item, int count) {
            String key = item + "|" + count;
            ObtainPlan cached = plans.get(key);
            if (cached != null) {
                reuses++;
                return cached;
            }
            ObtainPlan plan = ObtainPlanner.plan(item, count, inventory, book, drops, facts, tradingContext);
            plans.put(key, plan);
            return plan;
        }

        int passes() {
            return plans.size();
        }

        int reuses() {
            return reuses;
        }
    }

    /**
     * The ways to meet one need here, ranked by {@link NeedAllocation}: the whole candidate family is planned once, so
     * a source that can only cover part of the deficit still contributes its part and a speculative route cannot win
     * over supply already at hand. A candidate whose plan stopped at the planner's work limit is unsettled rather
     * than unobtainable: it is remembered in {@link #limitedPlan} and never contributes.
     */
    private List<NeedAllocation.Leg> allocate(Search search, CraftPlanner.RawNeed need) {
        limitedPlan = null;
        List<NeedAllocation.Candidate> plans = new ArrayList<>();
        List<String> candidates = candidates(need);
        for (int preference = 0; preference < candidates.size(); preference++) {
            String candidate = candidates.get(preference);
            ObtainPlan plan = search.plan(candidate, need.count());
            if (plan.limited()) {
                limitedPlan = plan;
            }
            plans.add(new NeedAllocation.Candidate(candidate, plan, preference));
        }
        return NeedAllocation.allocate(need.count(), plans);
    }

    /**
     * The first candidate with a complete plan for the need, for a station lookup that must recognize an impossible
     * one instead of starting a child bound to fail. A partial plan is not enough here: a station either works or it
     * does not.
     */
    private String executable(Search search, CraftPlanner.RawNeed need) {
        List<NeedAllocation.Candidate> plans = new ArrayList<>();
        List<String> candidates = candidates(need);
        for (int preference = 0; preference < candidates.size(); preference++) {
            String candidate = candidates.get(preference);
            ObtainPlan plan = search.plan(candidate, need.count());
            if (plan.limited()) {
                limitedPlan = plan;
            }
            plans.add(new NeedAllocation.Candidate(candidate, plan, preference));
        }
        for (NeedAllocation.Candidate candidate : NeedAllocation.rank(plans)) {
            if (candidate.plan().actionable()) {
                return candidate.item();
            }
        }
        return null;
    }

    /**
     * The leg to try when nothing here supplies anything: the preferred candidate once, because its failure text is
     * what the owner then reads. Null when no candidate is left or the pass was work-limited, which is not evidence
     * that the material is absent.
     */
    private NeedAllocation.Leg fallback(CraftPlanner.RawNeed need) {
        List<String> candidates = candidates(need);
        if (candidates.isEmpty() || limitedPlan != null) {
            return null;
        }
        return new NeedAllocation.Leg(candidates.get(0), need.count());
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
            TaskContext c,
            CompanionEntity companion,
            CraftPlanner.RawNeed need,
            NeedAllocation.Leg leg,
            String target,
            ObtainPlan plan,
            WorldFacts facts) {
        // A leg is the part of the shared material need this source can cover; the child asks for that plus what
        // the bag already holds, because an ObtainTask succeeds once the total it was sent for is in the inventory.
        int wanted = leg.count() + AcquiredItems.have(companion, leg.item());
        ObtainTask child = obtain(leg.item(), wanted);
        // The pass was planned for the whole need, so it is offered only when it covers the child's smaller target.
        if (plan.supplied() >= wanted || plan.conditional()) {
            child.seedPlan(plan, facts, AcquiredItems.inventoryMap(companion), tradingContext);
        }
        return startChild(c, child, ChildKind.GATHER, need.label() + "|" + leg.item(), need.label(), target);
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
        lastChildStamp = current.progressStamp();
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
            stoppedChild = current;
            current = null;
        }
        childKind = ChildKind.NONE;
        c.companion().getNavigation().stop();
    }

    @Override
    public void onTerminal(TaskContext c) {
        if (current != null) {
            ObtainTask.safeTerminate(current, c);
            current = null;
        }
        if (stoppedChild != null) {
            ObtainTask.safeRelease(stoppedChild, c);
            stoppedChild = null;
        }
    }

    @Override
    public String describe() {
        return switch (childKind) {
            case GATHER -> "gathering " + childLabel + " for " + targetsText();
            case CRAFT -> "crafting " + childTarget;
            case NONE -> "planning " + targetsText();
        };
    }

    /**
     * One cheap per-tick reading of verified effects, after {@link #captureWorkCounts} refreshed the bag: a target
     * count that grew and the running child's own stamp. A child that keeps reporting attempts without moving either
     * leaves the order's stamp still, which is exactly what a stall report should say.
     */
    private void observeVerifiedProgress() {
        for (int i = 0; i < workVerified.length; i++) {
            if (workVerified[i] > lastVerified[i]) verifiedProgress += workVerified[i] - lastVerified[i];
            lastVerified[i] = workVerified[i];
        }
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

    /** A running child's declared wait governs the order; the batch itself has none of its own. */
    @Override
    public int waitingTicks() {
        return current == null ? -1 : current.waitingTicks();
    }

    @Override
    public int waitingLimitTicks() {
        return current == null || current.waitingTicks() < 0 ? 0 : current.waitingLimitTicks();
    }

    @Override
    public String debugStatus() {
        String inner = current == null ? "" : current.debugStatus();
        return "t=" + ticks + "/" + maxTicks + " children=" + children + "/" + maxChildren
                + " failedSources=" + attempts.entries().size() + " have=" + verifiedText()
                + " plans=" + planPasses + "/" + planReuses
                + (inner.isEmpty() ? "" : " | " + inner);
    }

    /** The carried counts of every target, so a line says what the order actually holds. */
    private String verifiedText() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < workVerified.length; i++) {
            if (i > 0) text.append(',');
            text.append(workVerified[i]).append('/').append(targets.get(i).count());
        }
        return text.toString();
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
