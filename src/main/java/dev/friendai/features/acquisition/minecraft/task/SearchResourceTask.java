package dev.friendai.features.acquisition.minecraft.task;

import dev.friendai.debug.WorkSet;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.features.acquisition.minecraft.search.SearchTargets;
import dev.friendai.features.knowledge.search.AbandonPolicy;
import dev.friendai.features.knowledge.search.EscortPolicy;
import dev.friendai.features.knowledge.search.ResourceSearchProfile;
import dev.friendai.features.knowledge.search.SearchHint;
import dev.friendai.features.knowledge.search.SearchSession;
import dev.friendai.features.knowledge.search.TargetKind;
import dev.friendai.features.navigation.pathing.Pos;
import dev.friendai.perception.resource.CaveEntranceObservation;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Caller-scoped search for one target kind; the engine is shared, only the target data differs. */
public final class SearchResourceTask implements Task {
    public static final String TYPE = "search_resource";
    private final TargetKind fixedTarget;
    private final String resource;
    private final String requestedStrategy;
    private final String scope;
    private final String heading;
    private final Caller caller;
    private final ExploreTaskFactory exploreTaskFactory;
    private Task driver;
    private SearchSession session;
    private TargetKind target;
    private SearchHint hint;
    private EscortPolicy escort;
    private String summary = "search has not started";
    private UUID ownerId;
    private int radius;
    private boolean ownerWasNear;

    public SearchResourceTask(String resource, String requestedStrategy, String scope, String heading, Caller caller) {
        this(null, resource, requestedStrategy, scope, heading, caller, ExploreTaskFactory.UNAVAILABLE);
    }

    public SearchResourceTask(
            String resource,
            String requestedStrategy,
            String scope,
            String heading,
            Caller caller,
            ExploreTaskFactory exploreTaskFactory) {
        this(null, resource, requestedStrategy, scope, heading, caller, exploreTaskFactory);
    }

    public SearchResourceTask(
            TargetKind target, String requestedStrategy, String scope, String heading, Caller caller) {
        this(target, null, requestedStrategy, scope, heading, caller, ExploreTaskFactory.UNAVAILABLE);
    }

    public SearchResourceTask(
            TargetKind target,
            String requestedStrategy,
            String scope,
            String heading,
            Caller caller,
            ExploreTaskFactory exploreTaskFactory) {
        this(target, null, requestedStrategy, scope, heading, caller, exploreTaskFactory);
    }

    private SearchResourceTask(
            TargetKind target,
            String resource,
            String requestedStrategy,
            String scope,
            String heading,
            Caller caller,
            ExploreTaskFactory exploreTaskFactory) {
        this.fixedTarget = target;
        this.resource = resource;
        this.requestedStrategy = requestedStrategy == null ? "auto" : requestedStrategy;
        this.scope = scope == null ? "local" : scope;
        this.heading = heading;
        this.caller = caller;
        this.exploreTaskFactory = exploreTaskFactory;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public boolean hasTimeout() {
        return false;
    }

    @Override
    public void start(TaskContext context) {
        var owner = context.companion().ownerUuid();
        if (owner == null || caller == null || caller.access().ordinal() < AccessLevel.TRUSTED.ordinal())
            throw new IllegalArgumentException("resource search is unavailable to this caller");
        var policy = context.resourceSearchSettings();
        var ledger = context.ownerKnowledge().searchLedger();
        if (!scope.equals("local") && !scope.equals("expedition"))
            throw new IllegalArgumentException("scope must be local or expedition");
        boolean expedition = scope.equals("expedition");
        if (expedition && (caller == null || caller.access() != AccessLevel.OWNER || !policy.allowExpeditions())) {
            throw new IllegalArgumentException("expeditions are disabled or owner-only");
        }
        if (expedition && !policy.chunkLoadingEnabled())
            throw new IllegalArgumentException("expeditions require companion chunk loading to be enabled");
        if (fixedTarget != null) {
            target = fixedTarget;
        } else {
            SearchTargets.Resolution resolved = SearchTargets.resolve(context, "resource", resource);
            if (!resolved.ok()) {
                summary = resolved.error();
                return;
            }
            target = resolved.kind();
        }
        String dimension = context.level().dimension().identifier().toString();
        ResourceSearchProfile profile = profile(target, dimension);
        if (profile.strategies().isEmpty()) {
            summary = "no search strategy for " + target.label() + " in " + dimension;
            return;
        }
        ResourceSearchProfile.Strategy strategy = target instanceof TargetKind.BlockFamily
                ? chooseStrategy(profile)
                : ResourceSearchProfile.Strategy.SURFACE;
        if (!profile.allows(dimension, strategy)) {
            summary = "unsupported search strategy " + strategy.name().toLowerCase(java.util.Locale.ROOT) + " for "
                    + target.label();
            return;
        }
        CaveEntranceObservation caveEntrance = null;
        if (strategy == ResourceSearchProfile.Strategy.CAVE) {
            caveEntrance = context.resources().observedCaveEntrances(context.companion()).stream()
                    .findFirst()
                    .orElse(null);
            if (caveEntrance == null) {
                summary = "no directly observed cave entrance is available for this search";
                return;
            }
        }
        if (!ready(context)) {
            summary = "not ready for resource search: need food and at least two free inventory slots";
            return;
        }
        boolean shallow = profile.shallowStone();
        Set<String> blockIds = target instanceof TargetKind.BlockFamily family ? family.blockIds() : Set.of();
        if (strategy == ResourceSearchProfile.Strategy.MINE && !miningReady(context, blockIds, shallow)) {
            summary = shallow
                    ? "not ready for shallow stone search: need a suitable pickaxe with 24 durability"
                    : "not ready for mining search: need a suitable durable pickaxe and 16 torches";
            return;
        }
        radius = expedition ? policy.expeditionRadius() : policy.localRadius();
        int ticks = expedition ? policy.expeditionTicks() : policy.localTicks();
        int excavationLimit = shallow ? 24 : 128;
        SearchSession.Limits requested = new SearchSession.Limits(radius, radius * 4.0, ticks, excavationLimit);
        Pos origin = position(context);
        long logicalTick = context.level().getServer().overworld().getGameTime();
        session = ledger.acquire(owner, dimension, target.coverageKey(), logicalTick, requested, origin);
        session.clampLimits(requested);
        hint = context.searchHints().get(owner, logicalTick).orElse(null);
        escort = new EscortPolicy(policy.escort());
        driver = new ResourceSearchDriver(
                session,
                target,
                profile,
                radius,
                expedition,
                strategy,
                heading,
                caveEntrance,
                hint,
                false,
                exploreTaskFactory);
        ownerId = owner;
        driver.start(context);
    }

    @Override
    public TaskStatus tick(TaskContext context) {
        if (driver == null || session == null) return TaskStatus.FAILED;
        ServerPlayer owner = context.level().getServer().getPlayerList().getPlayer(ownerId);
        boolean ownerOnline = owner != null;
        boolean autonomous = context.autonomous();
        if (!ownerId.equals(context.companion().ownerUuid()) || (!ownerOnline && !autonomous)) {
            stop(context);
            session.interrupt();
            summary = "search interrupted because the owner is unavailable";
            return TaskStatus.FAILED;
        }
        boolean sameDimension = ownerOnline
                && owner.level()
                        .dimension()
                        .identifier()
                        .equals(context.level().dimension().identifier());
        boolean ownerInCurrentDimension = !ownerOnline || sameDimension;
        // Night is a real risk only when nobody safe is at hand: an autonomous search or an expedition far from
        // home. A short owner-led local search keeps going; the owner chose to send it out.
        var origin = session.snapshot().origin();
        var here = position(context);
        double fromOrigin = Math.hypot((double) here.x() - origin.x(), (double) here.z() - origin.z());
        boolean nightRisk =
                context.level().isDarkOutside() && (autonomous || (scope.equals("expedition") && fromOrigin > 16));
        var abandon = AbandonPolicy.check(new AbandonPolicy.Facts(
                context.companion().getHealth(),
                context.companion().getMaxHealth(),
                context.foodLevel(),
                nightRisk,
                !context.level().canSeeSky(context.companion().blockPosition()),
                ownerOnline || autonomous,
                ownerInCurrentDimension));
        if (abandon.isPresent()) {
            stop(context);
            session.interrupt();
            summary = abandon.get().text();
            return TaskStatus.FAILED;
        }
        double ownerDistance =
                ownerOnline ? owner.position().distanceTo(context.companion().position()) : Double.MAX_VALUE;
        String dimension = context.level().dimension().identifier().toString();
        var ledger = context.ownerKnowledge().searchLedger();
        boolean asked = ledger.asked(ownerId, dimension, target.coverageKey());
        if (!ownerOnline && autonomous && !asked) {
            askOnce(context, "free time: searching for " + target.label() + " on its own", dimension);
        }
        boolean holding = driver instanceof ResourceSearchDriver search && search.holding();
        EscortPolicy.Decision decision = scope.equals("expedition")
                ? new EscortPolicy.Decision(EscortPolicy.Action.NONE, radius, "")
                : escort.decide(new EscortPolicy.Input(
                        ownerOnline, autonomous, sameDimension, ownerDistance, ownerWasNear, holding, radius, asked));
        switch (decision.action()) {
            case WAIT -> {
                if (!(driver instanceof ResourceSearchDriver search)) return TaskStatus.FAILED;
                if (!search.holding()) {
                    search.holdForOwner(context, decision.message());
                    context.companion().facing().lookAt(owner, 2);
                }
                summary = search.resultSummary();
                return TaskStatus.RUNNING;
            }
            case CAP_RADIUS -> {
                resume();
                clampRadius(decision.radiusCap());
            }
            case ASK_ONCE -> {
                resume();
                clampRadius(decision.radiusCap());
                askOnce(context, decision.message(), dimension);
            }
            case AUTONOMOUS -> {
                resume();
                askOnce(context, decision.message(), dimension);
            }
            case NONE -> resume();
        }
        ownerWasNear = decision.action() == EscortPolicy.Action.WAIT
                || (ownerOnline && ownerDistance <= escort.config().sightRadius());
        TaskStatus status = driver.tick(context);
        summary = driver.resultSummary();
        return status;
    }

    @Override
    public java.util.List<WorkSet> debugWorkSets() {
        return driver == null ? java.util.List.of() : driver.debugWorkSets();
    }

    private void resume() {
        if (driver instanceof ResourceSearchDriver search) search.resumeAfterOwner();
    }

    private void clampRadius(int cap) {
        if (cap <= 0) return;
        var snapshot = session.snapshot();
        if (snapshot.limits().radius() <= cap) return;
        session.clampLimits(new SearchSession.Limits(
                cap,
                Math.min(snapshot.limits().travelBlocks(), cap * 4.0),
                snapshot.limits().ticks(),
                snapshot.limits().excavationBlocks()));
        radius = Math.min(radius, cap);
    }

    private void askOnce(TaskContext context, String message, String dimension) {
        if (message == null || message.isBlank()) return;
        var ledger = context.ownerKnowledge().searchLedger();
        if (ledger.asked(ownerId, dimension, target.coverageKey())) return;
        ledger.markAsked(ownerId, dimension, target.coverageKey());
        context.postWorldNotice(ownerId, message);
    }

    @Override
    public void stop(TaskContext context) {
        if (driver != null) driver.stop(context);
        if (session != null && !session.terminal()) session.interrupt();
    }

    @Override
    public String describe() {
        TargetKind effective = target == null ? fixedTarget : target;
        return "searching for " + (effective == null ? resource : effective.label());
    }

    @Override
    public String resultSummary() {
        return summary;
    }

    private static ResourceSearchProfile profile(TargetKind target, String dimension) {
        if (target instanceof TargetKind.BlockFamily family) return family.profile();
        return new ResourceSearchProfile(
                target.coverageKey(),
                Set.of(dimension),
                OptionalInt.empty(),
                Set.of(ResourceSearchProfile.Strategy.SURFACE));
    }

    private ResourceSearchProfile.Strategy chooseStrategy(ResourceSearchProfile profile) {
        if (!requestedStrategy.equalsIgnoreCase("auto")) {
            try {
                return ResourceSearchProfile.Strategy.valueOf(requestedStrategy.toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("unknown search strategy: " + requestedStrategy);
            }
        }
        if (profile.strategies().contains(ResourceSearchProfile.Strategy.SURFACE))
            return ResourceSearchProfile.Strategy.SURFACE;
        if (profile.strategies().contains(ResourceSearchProfile.Strategy.MINE))
            return ResourceSearchProfile.Strategy.MINE;
        return ResourceSearchProfile.Strategy.CAVE;
    }

    private static Pos position(TaskContext context) {
        var pos = context.companion().blockPosition();
        return new Pos(pos.getX(), pos.getY(), pos.getZ());
    }

    private static boolean ready(TaskContext context) {
        if (context.foodLevel() < 6) return false;
        int empty = 0;
        for (int slot = 0; slot < context.companion().inventory().getContainerSize(); slot++)
            if (context.companion().inventory().getItem(slot).isEmpty()) empty++;
        return empty >= 2;
    }

    private static boolean miningReady(TaskContext context, Set<String> blockIds, boolean shallow) {
        int torches = 0;
        boolean pickaxe = false;
        java.util.List<ItemStack> equipment = new java.util.ArrayList<>();
        equipment.add(context.companion().getMainHandItem());
        for (int slot = 0; slot < context.companion().inventory().getContainerSize(); slot++) {
            equipment.add(context.companion().inventory().getItem(slot));
        }
        for (ItemStack stack : equipment) {
            if (stack.is(Items.TORCH)) torches += stack.getCount();
            if (!stack.is(ItemTags.PICKAXES)) continue;
            int remaining = stack.getMaxDamage() - stack.getDamageValue();
            boolean speedsTargets = blockIds.stream().anyMatch(blockId -> {
                Identifier id = Identifier.tryParse(blockId);
                return id != null
                        && BuiltInRegistries.BLOCK.containsKey(id)
                        && stack.getDestroySpeed(
                                        BuiltInRegistries.BLOCK.getValue(id).defaultBlockState())
                                > 1.0F;
            });
            if (remaining >= (shallow ? 24 : 160) && speedsTargets) pickaxe = true;
        }
        return pickaxe && (shallow || torches >= 16);
    }
}
