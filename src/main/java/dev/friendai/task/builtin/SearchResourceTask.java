package dev.friendai.task.builtin;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.friendai.brain.BrainEvent;
import dev.friendai.core.FriendaiServerState;
import dev.friendai.debug.WorkSet;
import dev.friendai.knowledge.search.AbandonPolicy;
import dev.friendai.knowledge.search.EscortPolicy;
import dev.friendai.knowledge.search.ResourceSearchProfile;
import dev.friendai.knowledge.search.SearchHint;
import dev.friendai.knowledge.search.SearchSession;
import dev.friendai.knowledge.search.TargetKind;
import dev.friendai.pathing.Pos;
import dev.friendai.perception.resource.CaveEntranceObservation;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.obtain.ResourceSearchDriver;
import dev.friendai.task.obtain.SearchTargets;
import dev.friendai.tool.AccessLevel;
import dev.friendai.tool.Caller;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
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
    public static final String SAVE_PREFIX = "resource_session:v2:";

    private final TargetKind fixedTarget;
    private final String resource;
    private final String requestedStrategy;
    private final String scope;
    private final String heading;
    private final Caller caller;
    private final UUID restoreSession;
    private Task driver;
    private SearchSession session;
    private TargetKind target;
    private SearchHint hint;
    private EscortPolicy escort;
    private String summary = "search has not started";
    private UUID ownerId;
    private int radius;
    private boolean ownerWasNear;
    private boolean restored;
    private boolean restoredFound;

    public SearchResourceTask(String resource, String requestedStrategy, String scope, String heading, Caller caller) {
        this(null, resource, requestedStrategy, scope, heading, caller, null, false, false);
    }

    public SearchResourceTask(
            TargetKind target, String requestedStrategy, String scope, String heading, Caller caller) {
        this(target, null, requestedStrategy, scope, heading, caller, null, false, false);
    }

    private SearchResourceTask(
            TargetKind target,
            String resource,
            String requestedStrategy,
            String scope,
            String heading,
            Caller caller,
            UUID restoreSession,
            boolean restored,
            boolean restoredFound) {
        this.fixedTarget = target;
        this.resource = resource;
        this.requestedStrategy = requestedStrategy == null ? "auto" : requestedStrategy;
        this.scope = scope == null ? "local" : scope;
        this.heading = heading;
        this.caller = caller;
        this.restoreSession = restoreSession;
        this.restored = restored;
        this.restoredFound = restoredFound;
    }

    /** Rebuilds an owner-principal search after a reload; the ledger keeps every spent unit. */
    public static SearchResourceTask restore(String saved) {
        if (saved == null || !saved.startsWith(SAVE_PREFIX)) return null;
        try {
            String json = new String(
                    Base64.getUrlDecoder().decode(saved.substring(SAVE_PREFIX.length())), StandardCharsets.UTF_8);
            JsonObject payload = JsonParser.parseString(json).getAsJsonObject();
            TargetKind kind = TargetKind.parse(payload.getAsJsonObject("target"));
            UUID sessionId = payload.has("session")
                    ? UUID.fromString(payload.get("session").getAsString())
                    : null;
            return new SearchResourceTask(
                    kind,
                    null,
                    payload.has("strategy") ? payload.get("strategy").getAsString() : "auto",
                    payload.has("scope") ? payload.get("scope").getAsString() : "local",
                    payload.has("heading") ? payload.get("heading").getAsString() : null,
                    null,
                    sessionId,
                    true,
                    payload.has("found") && payload.get("found").getAsBoolean());
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    @Override
    public String type() {
        return "search_resource";
    }

    @Override
    public boolean hasTimeout() {
        return false;
    }

    @Override
    public void start(TaskContext context) {
        var owner = context.companion().ownerUuid();
        if (owner == null
                || (!restored && (caller == null || caller.access().ordinal() < AccessLevel.TRUSTED.ordinal())))
            throw new IllegalArgumentException("resource search is unavailable to this caller");
        var serverState = FriendaiServerState.get(context.level().getServer());
        var policy = serverState.config().tasks.resources;
        var ledger = serverState.knowledge().book(owner).searchLedger();
        if (!scope.equals("local") && !scope.equals("expedition"))
            throw new IllegalArgumentException("scope must be local or expedition");
        boolean expedition = scope.equals("expedition");
        if (expedition && (caller == null || caller.access() != AccessLevel.OWNER || !policy.allowExpeditions)) {
            if (restored) {
                if (restoreSession != null) ledger.interrupt(restoreSession);
                summary = "an expedition does not resume after a reload; ask again";
                return;
            }
            throw new IllegalArgumentException("expeditions are disabled or owner-only");
        }
        if (expedition && !serverState.config().companion.chunkLoading.enabled)
            throw new IllegalArgumentException("expeditions require companion chunk loading to be enabled");

        if (fixedTarget != null) {
            target = fixedTarget;
        } else {
            SearchTargets.Resolution resolved = SearchTargets.resolve(context.companion(), "resource", resource);
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
            caveEntrance = serverState.resources().observedCaveEntrances(context.companion()).stream()
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

        radius = expedition ? policy.expeditionRadius : policy.localRadius;
        int ticks = expedition ? policy.expeditionTicks : policy.localTicks;
        int excavationLimit = shallow ? 24 : 128;
        SearchSession.Limits requested = new SearchSession.Limits(radius, radius * 4.0, ticks, excavationLimit);
        Pos origin = position(context);
        long logicalTick = context.level().getServer().overworld().getGameTime();
        session = restoreSession == null ? null : ledger.session(restoreSession);
        if (restoreSession != null && session == null) {
            summary = "saved search session is unavailable; ask again";
            return;
        }
        if (session != null && session.terminal() && session.snapshot().phase() != SearchSession.Phase.INTERRUPTED) {
            summary = "saved search already finished";
            session = null;
            return;
        }
        if (session != null) {
            session.resumeAfterReload(logicalTick);
            session.clampLimits(requested);
            radius = session.snapshot().limits().radius();
        } else {
            session = ledger.acquire(owner, dimension, target.coverageKey(), logicalTick, requested, origin);
            session.clampLimits(requested);
        }
        hint = serverState.searchHints().get(owner, logicalTick).orElse(null);
        escort = new EscortPolicy(policy.escort());
        driver = new ResourceSearchDriver(
                session, target, profile, radius, expedition, strategy, heading, caveEntrance, hint, restoredFound);
        ownerId = owner;
        driver.start(context);
    }

    @Override
    public TaskStatus tick(TaskContext context) {
        if (driver == null || session == null) return TaskStatus.FAILED;
        ServerPlayer owner = context.level().getServer().getPlayerList().getPlayer(ownerId);
        boolean ownerOnline = owner != null;
        boolean autonomous = FriendaiServerState.get(context.level().getServer())
                        .freeTime()
                        .state(ownerId)
                        .shift()
                != null;
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
                context.companion().hunger().food(),
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
        var ledger = FriendaiServerState.get(context.level().getServer())
                .knowledge()
                .book(ownerId)
                .searchLedger();
        boolean asked = ledger.asked(ownerId, dimension, target.coverageKey());
        if (!ownerOnline && autonomous && !asked) {
            askOnce(
                    FriendaiServerState.get(context.level().getServer()),
                    "free time: searching for " + target.label() + " on its own",
                    dimension);
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
                askOnce(serverState(context), decision.message(), dimension);
            }
            case AUTONOMOUS -> {
                resume();
                askOnce(serverState(context), decision.message(), dimension);
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

    private void askOnce(FriendaiServerState state, String message, String dimension) {
        if (message == null || message.isBlank()) return;
        var ledger = state.knowledge().book(ownerId).searchLedger();
        if (ledger.asked(ownerId, dimension, target.coverageKey())) return;
        ledger.markAsked(ownerId, dimension, target.coverageKey());
        state.brains().post(ownerId, new BrainEvent.WorldNotice(message));
    }

    private static FriendaiServerState serverState(TaskContext context) {
        return FriendaiServerState.get(context.level().getServer());
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

    @Override
    public String saveData() {
        if (session == null || target == null) return null;
        try {
            JsonObject payload = new JsonObject();
            payload.add("target", target.toJson());
            payload.addProperty("session", session.snapshot().id().toString());
            payload.addProperty("strategy", requestedStrategy);
            payload.addProperty("scope", scope);
            if (heading != null) payload.addProperty("heading", heading);
            payload.addProperty("found", driver instanceof ResourceSearchDriver search && search.discovered());
            String json = payload.toString();
            return SAVE_PREFIX
                    + Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        } catch (RuntimeException broken) {
            return null;
        }
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
        if (context.companion().hunger().food() < 6) return false;
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
