package dev.friendai.features.acquisition.minecraft.task;

import dev.friendai.debug.DebugTarget;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.log.TaskLogSpan;
import dev.friendai.execution.tool.Caller;
import dev.friendai.features.acquisition.AcquisitionAttempts;
import dev.friendai.features.acquisition.ObtainCapacity;
import dev.friendai.features.acquisition.ObtainPlan;
import dev.friendai.features.acquisition.ObtainPlanner;
import dev.friendai.features.acquisition.ObtainStep;
import dev.friendai.features.acquisition.ObtainText;
import dev.friendai.features.acquisition.Source;
import dev.friendai.features.acquisition.TradingContext;
import dev.friendai.features.acquisition.WorldFacts;
import dev.friendai.features.acquisition.minecraft.ObtainSubtaskFactory;
import dev.friendai.features.acquisition.minecraft.search.DropIndex;
import dev.friendai.features.acquisition.minecraft.search.WorldScan;
import dev.friendai.features.crafting.CraftPlan;
import dev.friendai.features.crafting.CraftPlanner;
import dev.friendai.features.crafting.CraftStep;
import dev.friendai.features.crafting.Ingredient;
import dev.friendai.features.crafting.Station;
import dev.friendai.features.crafting.minecraft.RecipeIndex;
import dev.friendai.features.crafting.minecraft.Stations;
import dev.friendai.features.creative.CreativeAction;
import dev.friendai.features.creative.minecraft.CreativeItemCapability;
import dev.friendai.perception.resource.ResourceCandidates;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.interact.ContainerAccess;
import dev.friendai.task.interact.ContainerTakeRun;
import dev.friendai.task.interact.ContainerTour;
import dev.friendai.task.interact.MakeRoomTask;
import dev.friendai.task.inventory.ItemMatcher;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.ToDoubleFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

/**
 * Gets an item by any means: scans, plans the whole chain, runs the first step with the matching task and
 * re-plans from the new state after each step. Stops on its step and tick limits, or when the same step fails
 * twice in a row.
 */
public final class ObtainTask implements Task {
    public static final int DEFAULT_MAX_STEPS = 40;
    public static final int DEFAULT_MAX_TICKS = 24_000;
    public static final int DEFAULT_EXPLORE_RADIUS = 64;
    public static final int DEFAULT_EXPLORE_BLOCKS = 128;
    public static final int DEFAULT_EXPLORE_TICKS = 6_000;
    private static final int NOTICE_INTERVAL = 600;
    private static final int CONTAINER_RADIUS = 16;
    /** How many room-making trips one acquisition may make, so a bag that keeps refilling cannot walk forever. */
    private static final int MAX_ROOM_RUNS = 8;

    private final String item;
    private final int count;
    private final boolean useContainers;
    private final boolean explore;
    private final int maxSteps;
    private final int maxTicks;
    private final int exploreRadius;
    private final int exploreBlocks;
    private final int exploreTicks;
    private final String searchScope;
    private final Caller caller;
    private final TradingContext tradingContext;
    private final ObtainSubtaskFactory subtaskFactory;
    /** What this acquisition already tried, so the next plan must offer a different source or route. */
    private final AcquisitionAttempts attempts;

    private final List<String> history = new ArrayList<>();
    private final Set<Long> excludedSiteIds = new LinkedHashSet<>();
    /** Containers this acquisition found unusable, so a later step does not walk back to them. */
    private final Set<Long> unusableContainers = new LinkedHashSet<>();

    private Task current;
    /** A look through nearby storage, run once before making or gathering the item. */
    private Task tour;
    /** Frees room through the normal container interaction; ticks between steps like the tour. */
    private Task roomTask;
    /** The container the current room trip is using, so a refusal retires that candidate. */
    private BlockPos roomTarget;
    /** Candidates this acquisition already failed to make room in, so it does not walk back to them. */
    private final Set<Long> roomRefusals = new LinkedHashSet<>();
    /** The last trip's own reason, kept so a final "inventory is full" still says what was tried. */
    private String roomFailure;

    private int roomRuns;

    private boolean touredContainers;
    private TaskContext childContext;
    private TaskLogSpan span;
    private Supplier<ResourceCandidates.Verification> currentVisitVerification;
    private ObtainStep currentStep;
    private boolean creativeCreationStep;
    private ItemStack creativePrototype;
    private long creativeRequested;
    private long creativeDelivered;
    private String summary = "";
    private String lastFailedKey;
    private int ticks;
    private int stepsRun;
    private int lastNoticeTick = -NOTICE_INTERVAL;

    public ObtainTask(String item, int count, boolean useContainers, boolean explore) {
        this(
                item,
                count,
                useContainers,
                explore,
                DEFAULT_MAX_STEPS,
                DEFAULT_MAX_TICKS,
                DEFAULT_EXPLORE_RADIUS,
                DEFAULT_EXPLORE_BLOCKS,
                DEFAULT_EXPLORE_TICKS,
                "local",
                null,
                null,
                ObtainSubtaskFactory.UNAVAILABLE);
    }

    public ObtainTask(
            String item, int count, boolean useContainers, boolean explore, ObtainSubtaskFactory subtaskFactory) {
        this(
                item,
                count,
                useContainers,
                explore,
                DEFAULT_MAX_STEPS,
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
    public ObtainTask(
            String item,
            int count,
            boolean useContainers,
            boolean explore,
            int maxSteps,
            int maxTicks,
            int exploreRadius,
            int exploreBlocks,
            int exploreTicks) {
        this(
                item,
                count,
                useContainers,
                explore,
                maxSteps,
                maxTicks,
                exploreRadius,
                exploreBlocks,
                exploreTicks,
                "local",
                null,
                null,
                ObtainSubtaskFactory.UNAVAILABLE);
    }

    public ObtainTask(
            String item,
            int count,
            boolean useContainers,
            boolean explore,
            int maxSteps,
            int maxTicks,
            int exploreRadius,
            int exploreBlocks,
            int exploreTicks,
            String searchScope,
            Caller caller) {
        this(
                item,
                count,
                useContainers,
                explore,
                maxSteps,
                maxTicks,
                exploreRadius,
                exploreBlocks,
                exploreTicks,
                searchScope,
                caller,
                null,
                ObtainSubtaskFactory.UNAVAILABLE);
    }

    public ObtainTask(
            String item,
            int count,
            boolean useContainers,
            boolean explore,
            int maxSteps,
            int maxTicks,
            int exploreRadius,
            int exploreBlocks,
            int exploreTicks,
            String searchScope,
            Caller caller,
            TradingContext tradingContext,
            ObtainSubtaskFactory subtaskFactory) {
        this(
                item,
                count,
                useContainers,
                explore,
                maxSteps,
                maxTicks,
                exploreRadius,
                exploreBlocks,
                exploreTicks,
                searchScope,
                caller,
                tradingContext,
                subtaskFactory,
                new AcquisitionAttempts());
    }

    /** Shares one attempt ledger with a batch, so a failed source is not retried by the next gather child. */
    ObtainTask(
            String item,
            int count,
            boolean useContainers,
            boolean explore,
            int maxSteps,
            int maxTicks,
            int exploreRadius,
            int exploreBlocks,
            int exploreTicks,
            String searchScope,
            Caller caller,
            TradingContext tradingContext,
            ObtainSubtaskFactory subtaskFactory,
            AcquisitionAttempts attempts) {
        this.item = item;
        this.count = count;
        this.useContainers = useContainers;
        this.explore = explore;
        this.maxSteps = maxSteps;
        this.maxTicks = maxTicks;
        this.exploreRadius = exploreRadius;
        this.exploreBlocks = exploreBlocks;
        this.exploreTicks = exploreTicks;
        this.searchScope = searchScope == null ? "local" : searchScope;
        this.caller = caller;
        this.tradingContext = tradingContext;
        this.subtaskFactory = subtaskFactory == null ? ObtainSubtaskFactory.UNAVAILABLE : subtaskFactory;
        this.attempts = attempts == null ? new AcquisitionAttempts() : attempts;
    }

    @Override
    public String type() {
        return "obtain_item";
    }

    /** The item this job was sent for is not path material while it is still being gathered. */
    @Override
    public java.util.Map<String, Integer> materialNeeds(TaskContext context) {
        int missing = count - have(context.companion());
        if (missing <= 0) {
            return java.util.Map.of();
        }
        String id = ItemMatcher.parse(item).map(ItemMatcher::label).orElse("");
        return id.isBlank() ? java.util.Map.of() : java.util.Map.of(id, missing);
    }

    @Override
    public java.util.Map<String, Integer> travelMaterialNeeds(TaskContext context) {
        return current == null ? java.util.Map.of() : current.travelMaterialNeeds(context);
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
        currentVisitVerification = null;
        currentStep = null;
        creativeCreationStep = false;
        creativePrototype = null;
        creativeRequested = 0;
        creativeDelivered = 0;
        summary = "";
        lastFailedKey = null;
        ticks = 0;
        stepsRun = 0;
        lastNoticeTick = -NOTICE_INTERVAL;
        history.clear();
        excludedSiteIds.clear();
        unusableContainers.clear();
        roomRefusals.clear();
        roomTask = null;
        roomTarget = null;
        roomFailure = null;
        roomRuns = 0;
    }

    @Override
    public TaskStatus tick(TaskContext c) {
        if (++ticks > maxTicks) {
            return fail("ran out of time; have " + have(c.companion()) + " of " + count + " " + item);
        }
        if (creativeCreationStep) {
            CreativeItemCapability.Batch batch = CreativeItemCapability.deliverOneBatch(
                    c,
                    creativePrototype,
                    creativeRequested - creativeDelivered,
                    CreativeItemCapability.Destination.COMPANION);
            creativeDelivered += batch.inserted();
            if (!batch.succeeded()) {
                String creationSummary = CreativeItemCapability.resultSummary(
                        creativePrototype,
                        creativeDelivered,
                        creativeRequested,
                        CreativeItemCapability.failureText(
                                batch.failure(), CreativeItemCapability.Destination.COMPANION));
                clearCreativeCreation();
                return fail("couldn't finish creative delivery: " + creationSummary);
            }
            if (creativeDelivered == creativeRequested) {
                clearCreativeCreation();
                return succeed();
            }
            return TaskStatus.RUNNING;
        }
        if (tour != null) {
            TaskStatus status = tour.tick(c);
            if (status == TaskStatus.RUNNING) {
                return TaskStatus.RUNNING;
            }
            String toured = tour.resultSummary();
            safeTerminate(tour, c);
            tour = null;
            if (!toured.isBlank()) history.add(toured);
            // What the chests hold is known now: plan again from it.
            return startNext(c);
        }
        if (roomTask != null) {
            TaskStatus status = roomTask.tick(c);
            if (status == TaskStatus.RUNNING) {
                return TaskStatus.RUNNING;
            }
            String madeRoom = roomTask.resultSummary();
            if (!madeRoom.isBlank()) history.add(madeRoom);
            safeTerminate(roomTask, c);
            roomTask = null;
            if (status != TaskStatus.SUCCESS && roomTarget != null) {
                // This container could not help; another authorized one may, within the run bound.
                roomRefusals.add(roomTarget.asLong());
                roomFailure = madeRoom;
            }
            roomTarget = null;
            // Room may exist now, or the next candidate may be able to make it: plan from the live bag.
            return startNext(c);
        }
        if (current != null) {
            TaskStatus status = current.tick(childContext == null ? c : childContext);
            if (status == TaskStatus.RUNNING) {
                return TaskStatus.RUNNING;
            }
            String subSummary = current.resultSummary();
            ResourceCandidates.Verification visitVerification = currentVisitVerification != null
                    ? currentVisitVerification.get()
                    : dev.friendai.perception.resource.ResourceCandidates.Verification.PENDING;
            safeTerminate(current, childContext == null ? c : childContext);
            if (span != null) {
                span.finish(
                        status.name().toLowerCase(java.util.Locale.ROOT),
                        status == TaskStatus.SUCCESS ? "child_succeeded" : "child_failed",
                        java.util.Map.of());
                span = null;
            }
            childContext = null;
            ObtainStep step = currentStep;
            current = null;
            currentVisitVerification = null;
            currentStep = null;
            if (status == TaskStatus.SUCCESS) {
                stepsRun++;
                history.add(pastText(step));
                lastFailedKey = null;
            } else {
                if (step instanceof ObtainStep.VisitResource visit
                        && visitVerification
                                != dev.friendai.perception.resource.ResourceCandidates.Verification.PRESENT) {
                    excludedSiteIds.add(visit.siteId());
                }
                recordFailedSource(step, subSummary);
                String key = stepKey(step);
                if (key.equals(lastFailedKey)) {
                    return fail("stuck at " + ObtainText.stepText(step) + ": " + subSummary);
                }
                lastFailedKey = key;
                return TaskStatus.RUNNING;
            }
        }
        return startNext(c);
    }

    private TaskStatus startNext(TaskContext c) {
        CompanionEntity companion = c.companion();
        int have = have(companion);
        if (have >= count) {
            return succeed();
        }
        if (c.canUseCreativeAction(CreativeAction.CREATE_ITEM)) {
            Identifier id = Identifier.tryParse(item);
            if (id != null && BuiltInRegistries.ITEM.containsKey(id)) {
                ItemStack prototype = new ItemStack(BuiltInRegistries.ITEM.getValue(id));
                if (!prototype.isEmpty()) {
                    creativePrototype = prototype.copyWithCount(1);
                    creativeRequested = (long) count - have;
                    creativeDelivered = 0;
                    currentStep = null;
                    creativeCreationStep = true;
                    return TaskStatus.RUNNING;
                }
            }
        }
        if (stepsRun >= maxSteps) {
            return fail("gave up after " + maxSteps + " steps; have " + have + " of " + count + " " + item);
        }
        ServerLevel level = c.level();
        WorldFacts facts = WorldScan.scan(
                        level, companion, WorldScan.BLOCK_RADIUS, useContainers, explore, Set.of(), excludedSiteIds)
                .withAttemptedSources(attempts.sourceFailures());
        ObtainPlan plan = ObtainPlanner.plan(
                item,
                count,
                AcquiredItems.inventoryMap(companion),
                RecipeIndex.of(level),
                DropIndex.of(level),
                facts,
                tradingContext);
        if (shouldTour(plan)) {
            touredContainers = true;
            List<BlockPos> targets =
                    ContainerTour.unchecked(c, ContainerTour.NEAR_RADIUS, ContainerTour.MAX_CONTAINERS);
            if (!targets.isEmpty()) {
                tour = new ContainerTour(targets);
                tour.start(c);
                c.debug("obtain " + item + ": checking " + targets.size() + " containers nearby before going out");
                return TaskStatus.RUNNING;
            }
        }
        if (!plan.actionable()) {
            return fail(ObtainText.failureText(plan, item, count, !explore));
        }
        if (plan.steps().isEmpty()) {
            return fail("can't get " + count + " " + item + " here: nothing to do");
        }
        switch (prepareRoom(c, plan, plan.steps().get(0))) {
            case Room.Ready ignored -> {}
            case Room.Started ignored -> {
                return TaskStatus.RUNNING;
            }
            case Room.Blocked blocked -> {
                return fail(blocked.reason());
            }
        }
        currentStep = plan.steps().get(0);
        c.decision(
                dev.friendai.foundation.diagnostics.LogEvents.RESOURCE_DECISION,
                "step_selected",
                obtainDecisionFacts(plan, currentStep, have));
        current = subtask(c, currentStep);
        if (ticks - lastNoticeTick >= NOTICE_INTERVAL) {
            lastNoticeTick = ticks;
            c.debug("obtain " + item + ": " + ObtainText.stepText(currentStep) + " (step " + (stepsRun + 1) + ")");
        }
        current.start(childContext == null ? c : childContext);
        return TaskStatus.RUNNING;
    }

    /** Already-computed evidence: no second scan, no candidate graph, bounded text. */
    private java.util.Map<String, String> obtainDecisionFacts(ObtainPlan plan, ObtainStep step, int have) {
        java.util.Map<String, String> facts = new java.util.LinkedHashMap<>();
        facts.put("step", step.getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT));
        facts.put("stepNumber", Integer.toString(stepsRun + 1));
        facts.put("steps", Integer.toString(plan.steps().size()));
        facts.put("have", Integer.toString(have));
        facts.put("excludedSites", Integer.toString(excludedSiteIds.size()));
        facts.put("attemptedSources", Integer.toString(attempts.entries().size()));
        String missing = ObtainText.missingText(plan.missing(), !explore);
        if (!missing.isBlank()) facts.put("missing", missing);
        return facts;
    }

    private Task subtask(TaskContext c, ObtainStep step) {
        // One span per composed child: it opens when the child is actually created and closes when it terminates.
        if (span != null) span.finish("replaced", "next_step", java.util.Map.of());
        span = c.beginSpan(
                step.getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT),
                "obtain_step",
                java.util.Map.of("step", Integer.toString(stepsRun + 1)));
        childContext = c.forSpan(span);
        if (step instanceof ObtainStep.Take take) {
            currentVisitVerification = null;
            return new TakeFromContainerTask(itemMatcher(take.item()), take.count(), unusableContainers);
        }
        ObtainSubtaskFactory.Created created = subtaskFactory.create(
                c,
                step,
                new ObtainSubtaskFactory.Options(
                        searchScope, caller, tradingContext, exploreRadius, exploreBlocks, exploreTicks));
        currentVisitVerification = created.visitVerification();
        return created.task();
    }

    /**
     * Makes room for the upcoming step only when that step genuinely needs it. The need is measured, not assumed:
     * a produced item first fills the room left in a compatible carried stack (63 cobblestone need no free slot for
     * the 64th), and an ingredient the step consumes first can free the slot its result takes (a log becomes planks).
     * The space is freed through the normal container interaction — walk, protection, open, deposit, record — never by
     * writing into a container at range, and only unrelated stacks move: never the target, a plan item, a reserved
     * material or a station the step will use.
     */
    private Room prepareRoom(TaskContext c, ObtainPlan plan, ObtainStep step) {
        CompanionEntity companion = c.companion();
        StepDemand demand = demandOf(c, step);
        int needed = ObtainCapacity.emptySlotsNeeded(demand.produced(), demand.consumed(), bag(companion));
        if (emptySlots(companion) >= needed) {
            return READY;
        }
        if (!useContainers || roomRuns >= MAX_ROOM_RUNS) {
            return blockedRoom();
        }
        BlockPos pos = roomCandidate(c);
        if (pos == null) {
            return blockedRoom();
        }
        Set<String> keep = itemsUsed(plan);
        keep.add(item);
        keep.addAll(demand.keep());
        keep.addAll(c.materialReserve().asMap().keySet());
        roomRuns++;
        roomTarget = pos;
        roomTask = new MakeRoomTask(pos, needed, keep);
        roomTask.start(c);
        return STARTED;
    }

    /** The next authorized room-making destination: a remembered or visible container nearby, nearest first. */
    private BlockPos roomCandidate(TaskContext c) {
        List<BlockPos> candidates = ContainerAccess.known(c, CONTAINER_RADIUS);
        LinkedHashSet<BlockPos> seen = new LinkedHashSet<>();
        for (BlockPos pos : candidates) {
            if (!seen.add(pos) || roomRefusals.contains(pos.asLong())) continue;
            if (!c.companion().hands().canInteract(pos)) continue;
            if (ContainerAccess.at(c.level(), pos).isEmpty()) continue;
            return pos;
        }
        return null;
    }

    /** The blocker the planner matches, carrying the last trip's own evidence when there is any. */
    private Room blockedRoom() {
        return new Room.Blocked(
                roomFailure == null || roomFailure.isBlank()
                        ? "my inventory is full"
                        : "my inventory is full; " + roomFailure);
    }

    /** The room decision for one step: continue, wait for the trip, or stop with the reason. */
    private sealed interface Room {
        record Ready() implements Room {}

        record Started() implements Room {}

        record Blocked(String reason) implements Room {}
    }

    private static final Room READY = new Room.Ready();
    private static final Room STARTED = new Room.Started();

    /** What one step adds to and takes out of the inventory, plus the items it must keep in the bag. */
    private record StepDemand(Map<String, Integer> produced, Map<String, Integer> consumed, Set<String> keep) {}

    private StepDemand demandOf(TaskContext c, ObtainStep step) {
        Map<String, Integer> produced = new LinkedHashMap<>();
        Map<String, Integer> consumed = new LinkedHashMap<>();
        Set<String> keep = new LinkedHashSet<>();
        switch (step) {
            case ObtainStep.Take take -> produced.put(take.item(), take.count());
            case ObtainStep.Craft craft -> craftDemand(c, craft, produced, consumed, keep);
            case ObtainStep.Smelt smelt -> {
                produced.put(smelt.output(), smelt.count());
                consumed.put(smelt.input(), smelt.count());
                keep.add(smelt.input());
            }
            case ObtainStep.Mine mine ->
                produced.put(
                        item,
                        producedBy(
                                c,
                                item,
                                mine.count(),
                                source -> source instanceof Source.Mine candidate
                                        && candidate.block().equals(mine.block()),
                                source -> ((Source.Mine) source).perBlock()));
            case ObtainStep.Hunt hunt ->
                produced.put(
                        item,
                        producedBy(
                                c,
                                item,
                                hunt.count(),
                                source -> source instanceof Source.Hunt candidate
                                        && candidate.mob().equals(hunt.mob()),
                                source -> ((Source.Hunt) source).perKill()));
            case ObtainStep.Harvest harvest ->
                produced.put(
                        item,
                        producedBy(
                                c,
                                item,
                                harvest.count(),
                                source -> source instanceof Source.Harvest candidate
                                        && candidate.crop().equals(harvest.crop()),
                                source -> ((Source.Harvest) source).perBlock()));
            case ObtainStep.Shear shear ->
                produced.put(
                        item,
                        producedBy(
                                c,
                                item,
                                shear.count(),
                                source -> source instanceof Source.Shear candidate
                                        && candidate.mob().equals(shear.mob()),
                                source -> ((Source.Shear) source).perAnimal()));
            case ObtainStep.Milk milk -> {
                produced.put("minecraft:milk_bucket", milk.count());
                consumed.put("minecraft:bucket", milk.count());
                keep.add("minecraft:bucket");
            }
            case ObtainStep.Trade trade -> produced.put(item, trade.count());
            case ObtainStep.VisitResource ignored -> {}
            case ObtainStep.Explore ignored -> {}
            case ObtainStep.InspectTrader ignored -> {}
        }
        keep.addAll(produced.keySet());
        keep.addAll(consumed.keySet());
        return new StepDemand(produced, consumed, keep);
    }

    /**
     * What a step that yields drops really adds: its step count times the expected yield the drop index knows for
     * that item and source, rounded up and never below one item per step. The index is the same one the plan was
     * built from, so this neither reads the world again nor guesses a number the plan did not use.
     */
    private static int producedBy(
            TaskContext c, String item, int stepCount, Predicate<Source> match, ToDoubleFunction<Source> yield) {
        double per = DropIndex.of(c.level()).sourcesOf(item).stream()
                .filter(match)
                .mapToDouble(yield)
                .findFirst()
                .orElse(1.0);
        return Math.max(stepCount, (int) Math.ceil(stepCount * per));
    }

    /**
     * A craft consumes its ingredients before its result arrives, so the recipe plan decides both sides. The same
     * planner the craft task will run against is asked here, and its steps are replayed over a copy of the bag; a
     * plan that cannot be replayed falls back to the plain output count, which is the conservative answer.
     */
    private void craftDemand(
            TaskContext c,
            ObtainStep.Craft craft,
            Map<String, Integer> produced,
            Map<String, Integer> consumed,
            Set<String> keep) {
        RecipeIndex index = RecipeIndex.of(c.level());
        Map<String, Integer> inventory = AcquiredItems.inventoryMap(c.companion());
        CraftPlan plan = CraftPlanner.planNew(
                craft.item(), craft.count(), inventory, index, Stations.available(c.level(), c.companion()));
        if (!plan.ok() || plan.steps().isEmpty()) {
            produced.put(craft.item(), craft.count());
            return;
        }
        Map<String, Integer> sim = new LinkedHashMap<>(inventory);
        for (CraftStep step : plan.steps()) {
            if (step.recipe().station() != Station.INVENTORY) {
                String stationItem = index.stationItem(step.recipe().station());
                if (stationItem != null && !stationItem.isBlank()) {
                    keep.add(stationItem);
                }
            }
            for (int run = 0; run < step.times(); run++) {
                for (Ingredient ingredient : step.recipe().ingredients()) {
                    String chosen = null;
                    for (String candidate : ingredient.items()) {
                        if (sim.getOrDefault(candidate, 0) > 0) {
                            chosen = candidate;
                            break;
                        }
                    }
                    if (chosen == null) {
                        produced.clear();
                        consumed.clear();
                        produced.put(craft.item(), craft.count());
                        return;
                    }
                    sim.merge(chosen, -1, Integer::sum);
                    consumed.merge(chosen, 1, Integer::sum);
                }
                sim.merge(step.recipe().output(), step.recipe().outputCount(), Integer::sum);
                produced.merge(step.recipe().output(), step.recipe().outputCount(), Integer::sum);
            }
        }
    }

    /** The live bag, measured the way the game fills it. */
    private static ObtainCapacity.Inventory bag(CompanionEntity companion) {
        Container inventory = companion.inventory();
        return new ObtainCapacity.Inventory() {
            @Override
            public int freeRoom(String itemId) {
                return StackSpace.freeRoom(inventory, itemId);
            }

            @Override
            public int maxStack(String itemId) {
                return StackSpace.maxStack(itemId);
            }

            @Override
            public int emptiedSlots(String itemId, int consumed) {
                return StackSpace.emptiedSlots(inventory, itemId, consumed);
            }
        };
    }

    private static Set<String> itemsUsed(ObtainPlan plan) {
        Set<String> used = new LinkedHashSet<>();
        for (ObtainStep step : plan.steps()) {
            switch (step) {
                case ObtainStep.Take take -> used.add(take.item());
                case ObtainStep.Craft craft -> used.add(craft.item());
                case ObtainStep.Smelt smelt -> {
                    used.add(smelt.input());
                    used.add(smelt.output());
                }
                case ObtainStep.Mine mine -> {
                    used.add(mine.block());
                    if (mine.tool().vanillaItem() != null) {
                        used.add(mine.tool().vanillaItem());
                    }
                }
                case ObtainStep.VisitResource visit -> used.add(visit.block());
                case ObtainStep.Hunt hunt -> used.add(hunt.mob());
                case ObtainStep.Harvest harvest -> used.add(harvest.crop());
                case ObtainStep.Shear shear -> used.add(shear.mob());
                case ObtainStep.Milk ignored -> {}
                case ObtainStep.Explore ignored -> {}
                case ObtainStep.Trade ignored -> {}
                case ObtainStep.InspectTrader ignored -> {}
            }
        }
        return used;
    }

    private TaskStatus succeed() {
        StringBuilder text = new StringBuilder("got ")
                .append(count)
                .append(' ')
                .append(item)
                .append(" in ")
                .append(stepsRun)
                .append(" steps");
        if (!history.isEmpty()) {
            text.append(": ");
            int shown = Math.min(history.size(), 8);
            for (int i = 0; i < shown; i++) {
                if (i > 0) {
                    text.append(", ");
                }
                text.append(history.get(i));
            }
            if (history.size() > shown) {
                text.append(", …");
            }
        }
        summary = text.toString();
        return TaskStatus.SUCCESS;
    }

    private TaskStatus fail(String text) {
        summary = text;
        return TaskStatus.FAILED;
    }

    private int have(CompanionEntity companion) {
        return AcquiredItems.have(companion, item);
    }

    private static int emptySlots(CompanionEntity companion) {
        int empty = 0;
        for (int slot = 0; slot < companion.inventory().getContainerSize(); slot++) {
            if (companion.inventory().getItem(slot).isEmpty()) {
                empty++;
            }
        }
        return empty;
    }

    private static ItemMatcher itemMatcher(String id) {
        return ItemMatcher.parse(id).orElseThrow(() -> new IllegalArgumentException("unknown item: " + id));
    }

    /**
     * Whether to look through nearby storage first: once per task, when containers may be used and the next step does
     * not already take from a known container. Check for the finished item before spending materials or going out.
     */
    private boolean shouldTour(ObtainPlan plan) {
        if (touredContainers || !useContainers) return false;
        if (!plan.actionable() || plan.steps().isEmpty()) return true;
        ObtainStep first = plan.steps().get(0);
        return !(first instanceof ObtainStep.Take);
    }

    /**
     * Records the material source a failed step tried, so the next plan prefers another reachable source and stops
     * offering one that already failed twice. Only source-bearing steps count: a craft or a container take has no
     * source to exclude and its own repetition is already caught by {@link #lastFailedKey}.
     */
    private void recordFailedSource(ObtainStep step, String cause) {
        switch (step) {
            case ObtainStep.Mine mine -> attempts.recordFailure(item, mine.block(), cause);
            case ObtainStep.Explore explore -> attempts.recordFailure(item, explore.block(), cause);
            case ObtainStep.VisitResource visit -> attempts.recordFailure(item, visit.block(), cause);
            default -> {}
        }
    }

    private static String stepKey(ObtainStep step) {
        return switch (step) {
            case ObtainStep.Take take -> "take:" + take.item();
            case ObtainStep.Craft craft -> "craft:" + craft.item();
            case ObtainStep.Smelt smelt -> "smelt:" + smelt.input() + "->" + smelt.output();
            case ObtainStep.Mine mine -> "mine:" + mine.block();
            case ObtainStep.VisitResource visit ->
                "visit:" + visit.siteId() + ":" + visit.dimension() + ":" + visit.block();
            case ObtainStep.Hunt hunt -> "hunt:" + hunt.mob();
            case ObtainStep.Harvest harvest -> "harvest:" + harvest.crop();
            case ObtainStep.Shear shear -> "shear:" + shear.mob();
            case ObtainStep.Milk ignored -> "milk";
            case ObtainStep.Explore explore -> "explore:" + explore.block();
            case ObtainStep.Trade trade -> "trade:" + trade.trader() + ":" + trade.offerRef();
            case ObtainStep.InspectTrader inspect -> "inspect:" + inspect.trader();
        };
    }

    private static String pastText(ObtainStep step) {
        return switch (step) {
            case ObtainStep.Take take -> "took " + take.count() + " " + take.item();
            case ObtainStep.Craft craft ->
                craft.count() == 1 ? "crafted " + craft.item() : "crafted " + craft.count() + " × " + craft.item();
            case ObtainStep.Smelt smelt -> "smelted " + smelt.count() + " " + smelt.input();
            case ObtainStep.Mine mine -> "mined " + mine.count() + " " + mine.block();
            case ObtainStep.VisitResource visit -> "visited site " + visit.siteId() + " for " + visit.block();
            case ObtainStep.Hunt hunt -> "hunted " + hunt.count() + " " + hunt.mob();
            case ObtainStep.Harvest harvest -> "harvested " + harvest.count() + " " + harvest.crop();
            case ObtainStep.Shear shear -> "sheared " + shear.count() + " " + shear.mob();
            case ObtainStep.Milk milk -> "milked " + milk.count();
            case ObtainStep.Explore explore -> "explored for " + explore.block();
            case ObtainStep.Trade trade -> "traded " + trade.count() + " fill(s) with " + trade.trader();
            case ObtainStep.InspectTrader inspect -> "inspected " + inspect.trader();
        };
    }

    /** Stops a child task without letting a failing stop take the caller down with it. */
    static void safeStop(Task task, TaskContext c) {
        try {
            task.stop(c);
        } catch (RuntimeException ignored) {
            // A task that cannot stop cleanly must not take the whole obtain down with it.
        }
    }

    /** Releases a naturally settled child exactly once; outer cancellation remains stop-only. */
    static void safeTerminate(Task task, TaskContext c) {
        if (task == null) return;
        safeStop(task, c);
        try {
            task.onTerminal(c);
        } catch (RuntimeException ignored) {
            // A child's terminal hook must not prevent the acquisition planner from continuing.
        }
    }

    private void clearCreativeCreation() {
        creativeCreationStep = false;
        creativePrototype = null;
        creativeRequested = 0;
        creativeDelivered = 0;
        currentStep = null;
    }

    /** Acquisition owns this nested step; container access and inventory movement are shared helpers. */
    private static final class TakeFromContainerTask implements Task {
        private final ContainerTakeRun run;

        private TakeFromContainerTask(ItemMatcher matcher, int count, Set<Long> unusable) {
            // The chest the item was seen in, live records first; the nearest visible container only as a last resort.
            this.run = new ContainerTakeRun(
                    matcher,
                    count,
                    null,
                    context -> ContainerAccess.withdrawals(context, matcher::accepts, CONTAINER_RADIUS),
                    true,
                    () -> "no storage container with " + matcher.label() + " within " + CONTAINER_RADIUS + " blocks",
                    unusable);
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
        public List<DebugTarget> debugTargets() {
            return run.debugTargets();
        }
    }

    @Override
    public void stop(TaskContext c) {
        clearCreativeCreation();
        if (tour != null) {
            safeStop(tour, c);
            tour = null;
        }
        if (roomTask != null) {
            safeStop(roomTask, c);
            roomTask = null;
        }
        if (span != null) {
            span.finish("cancelled", "stopped", java.util.Map.of());
            span = null;
        }
        childContext = null;
        if (current != null) {
            safeStop(current, c);
            current = null;
        }
        c.companion().getNavigation().stop();
    }

    @Override
    public void onTerminal(TaskContext c) {
        if (current != null) {
            safeTerminate(current, childContext == null ? c : childContext);
            current = null;
        }
        if (tour != null) {
            safeTerminate(tour, c);
            tour = null;
        }
        if (span != null) {
            span.finish("finished", "terminal", java.util.Map.of());
            span = null;
        }
        childContext = null;
    }

    @Override
    public String describe() {
        String step = currentStep == null ? "planning" : ObtainText.stepText(currentStep);
        return "getting " + count + " " + item + ": step " + (stepsRun + 1) + " — " + step;
    }

    @Override
    public String debugStatus() {
        String step = currentStep == null ? "planning" : ObtainText.stepText(currentStep);
        String inner = current == null ? "" : current.debugStatus();
        return "step " + (stepsRun + 1) + "/" + maxSteps + " " + step
                + (inner.isEmpty() ? "" : " | " + inner)
                + " t=" + ticks + "/" + maxTicks;
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
    public String saveData() {
        return current == null ? null : current.saveData();
    }
}
