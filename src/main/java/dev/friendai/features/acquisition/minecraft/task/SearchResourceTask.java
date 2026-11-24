package dev.friendai.features.acquisition.minecraft.task;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.friendai.debug.WorkSet;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.features.acquisition.minecraft.search.SearchTargets;
import dev.friendai.features.knowledge.search.AbandonPolicy;
import dev.friendai.features.knowledge.search.EscortPolicy;
import dev.friendai.features.knowledge.search.ResourceSearchProfile;
import dev.friendai.features.knowledge.search.SearchHint;
import dev.friendai.features.knowledge.search.SearchLedger;
import dev.friendai.features.knowledge.search.SearchPlanner;
import dev.friendai.features.knowledge.search.SearchSession;
import dev.friendai.features.knowledge.search.TargetKind;
import dev.friendai.foundation.spatial.Pos;
import dev.friendai.perception.resource.CaveEntranceObservation;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.inventory.InventoryOps;
import dev.friendai.task.inventory.ItemMatcher;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.StructureTags;
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
    /** The session a checkpoint named; null for a fresh request. */
    private final UUID restoreSessionId;

    private Task driver;
    private SearchSession session;
    private TargetKind target;
    private SearchHint hint;
    private EscortPolicy escort;
    private String summary = "search has not started";
    /**
     * What the source resolution could not cover, kept so a failed result says the list may be incomplete rather than
     * reading as an exhaustive "not here".
     */
    private String coverageNote = "";

    private UUID ownerId;
    private int radius;
    private boolean ownerWasNear;
    private boolean rememberedStructure;

    public SearchResourceTask(String resource, String requestedStrategy, String scope, String heading, Caller caller) {
        this(null, resource, requestedStrategy, scope, heading, caller, ExploreTaskFactory.UNAVAILABLE, null);
    }

    public SearchResourceTask(
            String resource,
            String requestedStrategy,
            String scope,
            String heading,
            Caller caller,
            ExploreTaskFactory exploreTaskFactory) {
        this(null, resource, requestedStrategy, scope, heading, caller, exploreTaskFactory, null);
    }

    public SearchResourceTask(
            TargetKind target, String requestedStrategy, String scope, String heading, Caller caller) {
        this(target, null, requestedStrategy, scope, heading, caller, ExploreTaskFactory.UNAVAILABLE, null);
    }

    public SearchResourceTask(
            TargetKind target,
            String requestedStrategy,
            String scope,
            String heading,
            Caller caller,
            ExploreTaskFactory exploreTaskFactory) {
        this(target, null, requestedStrategy, scope, heading, caller, exploreTaskFactory, null);
    }

    /** Rebuilds a checkpointed search that resumes the named session with its remaining budget. */
    public SearchResourceTask(
            TargetKind target,
            String requestedStrategy,
            String scope,
            String heading,
            Caller caller,
            ExploreTaskFactory exploreTaskFactory,
            UUID restoreSessionId) {
        this(target, null, requestedStrategy, scope, heading, caller, exploreTaskFactory, restoreSessionId);
    }

    private SearchResourceTask(
            TargetKind target,
            String resource,
            String requestedStrategy,
            String scope,
            String heading,
            Caller caller,
            ExploreTaskFactory exploreTaskFactory,
            UUID restoreSessionId) {
        this.fixedTarget = target;
        this.resource = resource;
        this.requestedStrategy = requestedStrategy == null ? "auto" : requestedStrategy;
        this.scope = scope == null ? "local" : scope;
        this.heading = heading;
        this.caller = caller;
        this.exploreTaskFactory = exploreTaskFactory;
        this.restoreSessionId = restoreSessionId;
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
        // An eye of ender is spent once per order; a resumed search must not consume another clue.
        if (restoreSessionId == null) useStrongholdClue(context, owner);
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
            coverageNote = resolved.note();
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
        Pos here = position(context);
        long now = context.level().getServer().overworld().getGameTime();
        SearchSession resumed = restoreSessionId == null
                ? null
                : ledger.resume(restoreSessionId, owner, dimension, target.identity(), now);
        String recoveryNote;
        if (resumed != null) {
            session = resumed;
            session.clampLimits(requested);
            recoveryNote = "resumed after restart with its spent budget";
        } else {
            SearchLedger.Admission admission =
                    ledger.acquire(owner, dimension, target.identity(), now, requested, here);
            session = admission.session();
            session.clampLimits(requested);
            recoveryNote = restoreSessionId != null
                    ? "the interrupted search could not be resumed exactly; continuing under the ordinary budget"
                    : admission.resumed() ? "continuing the interrupted trip with its spent budget" : "";
        }
        // A resumed trip returns to the base it started from, not to wherever the restart put the companion.
        Pos base = resumed == null ? null : session.snapshot().origin();
        boolean resumeReturn = resumed != null && session.snapshot().phase() == SearchSession.Phase.RETURN;
        radius = Math.min(radius, session.snapshot().limits().radius());
        hint = context.searchHints().get(owner, now).orElse(null);
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
                exploreTaskFactory,
                base,
                resumeReturn,
                recoveryNote);
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
        boolean asked = ledger.asked(ownerId, dimension, target.identity());
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
        if (status == TaskStatus.FAILED && !coverageNote.isEmpty()) {
            summary = summary + " (" + coverageNote + ")";
        }
        if (status == TaskStatus.SUCCESS && target instanceof TargetKind.Structure structure && !rememberedStructure) {
            rememberedStructure = true;
            remember(structure, context);
        }
        return status;
    }

    @Override
    public java.util.List<WorkSet> debugWorkSets() {
        return driver == null ? java.util.List.of() : driver.debugWorkSets();
    }

    @Override
    public java.util.Map<String, Integer> travelMaterialNeeds(TaskContext context) {
        return driver == null ? java.util.Map.of() : driver.travelMaterialNeeds(context);
    }

    @Override
    public dev.friendai.task.Task cleanupAfterCancel(TaskContext context) {
        return driver == null ? null : driver.cleanupAfterCancel(context);
    }

    /** A structure seen by its markers becomes one landmark place, so a later search can travel to it. */
    private void remember(TargetKind.Structure structure, TaskContext context) {
        if (caller == null || context.companion().ownerUuid() == null) return;
        String dimension = context.level().dimension().identifier().toString();
        String shortId = structure.id().substring(structure.id().indexOf(':') + 1);
        String name = "structure " + shortId;
        try {
            var book = context.placeService().book(context.companion());
            if (book.find(name, dimension).isPresent()) return;
            var at = context.companion().blockPosition();
            context.placeService()
                    .remember(
                            context.companion(),
                            caller,
                            new dev.friendai.foundation.places.PlaceValues(
                                    name,
                                    dev.friendai.foundation.places.PlaceKind.LANDMARK,
                                    "",
                                    dimension,
                                    new dev.friendai.foundation.spatial.Pos(at.getX(), at.getY(), at.getZ()),
                                    64,
                                    java.util.Set.of(),
                                    dev.friendai.foundation.places.PlaceSource.STRUCTURE,
                                    caller.uuid(),
                                    "seen by its marker blocks"),
                            context.level().getGameTime());
        } catch (RuntimeException fullOrInvalid) {
            // A full place book must not turn a successful search into a failure.
        }
    }

    /**
     * An eye of ender is a clue, not a find: it is consumed once to steer the search toward the stronghold, and its
     * direction is stored as a short-lived hint. No coordinate is returned or reported as a structure.
     */
    private void useStrongholdClue(TaskContext context, UUID owner) {
        if (!(fixedTarget instanceof TargetKind.Structure structure)
                || !structure.id().equals("minecraft:stronghold")) {
            return;
        }
        var matcher = ItemMatcher.parse("minecraft:ender_eye").orElse(null);
        if (matcher == null || InventoryOps.count(context.companion().inventory(), matcher) < 1) return;
        var level = (net.minecraft.server.level.ServerLevel) context.level();
        var at = context.companion().blockPosition();
        InventoryOps.remove(context.companion().inventory(), matcher, 1);
        var found = level.findNearestMapStructure(StructureTags.EYE_OF_ENDER_LOCATED, at, 1_000, true);
        if (found == null) return;
        SearchPlanner.Heading direction = dev.friendai.features.knowledge.search.StrongholdClue.heading(
                new dev.friendai.foundation.spatial.Pos(at.getX(), at.getY(), at.getZ()),
                new dev.friendai.foundation.spatial.Pos(found.getX(), found.getY(), found.getZ()));
        context.searchHints()
                .put(
                        owner,
                        SearchHint.create(
                                direction,
                                "",
                                "",
                                null,
                                0,
                                "the eye of ender points this way",
                                context.level().getGameTime()));
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
        if (ledger.asked(ownerId, dimension, target.identity())) return;
        ledger.markAsked(ownerId, dimension, target.identity());
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

    @Override
    public String saveData() {
        if (driver == null || session == null || target == null || caller == null || caller.uuid() == null) return null;
        SearchSession.Snapshot snapshot = session.snapshot();
        if (snapshot.phase() == SearchSession.Phase.COMPLETE || snapshot.phase() == SearchSession.Phase.FAILED)
            return null;
        return new Saved(target, requestedStrategy, scope, heading, caller.uuid(), snapshot.id()).encode();
    }

    /**
     * The bounded checkpoint a restart resumes: which search, by which actor, and the exact session whose spent
     * budget and origin must continue. Deliberately no access level, no journey and no lease: all three are
     * re-derived from current data before the restored task may act.
     */
    public record Saved(
            TargetKind target, String strategy, String scope, String heading, UUID principal, UUID session) {
        static final int VERSION = 1;
        static final int MAX_LENGTH = 8_192;

        String encode() {
            JsonObject json = new JsonObject();
            json.addProperty("v", VERSION);
            json.add("target", target.toJson());
            json.addProperty("strategy", strategy);
            json.addProperty("scope", scope);
            if (heading != null && !heading.isBlank()) json.addProperty("heading", heading);
            json.addProperty("principal", principal.toString());
            json.addProperty("session", session.toString());
            String encoded = json.toString();
            return encoded.length() <= MAX_LENGTH ? encoded : null;
        }

        /** Returns empty for malformed, oversized or unknown-version data; the job is then retried from its args. */
        public static java.util.Optional<Saved> decode(String data) {
            if (data == null || data.isBlank() || data.length() > MAX_LENGTH) return java.util.Optional.empty();
            try {
                JsonObject json = JsonParser.parseString(data).getAsJsonObject();
                if (!json.has("v") || json.get("v").getAsInt() != VERSION) return java.util.Optional.empty();
                TargetKind target = TargetKind.parse(json.getAsJsonObject("target"));
                UUID principal = UUID.fromString(json.get("principal").getAsString());
                UUID session = UUID.fromString(json.get("session").getAsString());
                String heading = json.has("heading") && !json.get("heading").isJsonNull()
                        ? json.get("heading").getAsString()
                        : null;
                return java.util.Optional.of(new Saved(
                        target,
                        text(json, "strategy", "auto"),
                        text(json, "scope", "local"),
                        heading,
                        principal,
                        session));
            } catch (RuntimeException invalid) {
                return java.util.Optional.empty();
            }
        }

        private static String text(JsonObject json, String field, String fallback) {
            return json.has(field) && !json.get(field).isJsonNull()
                    ? json.get(field).getAsString()
                    : fallback;
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
