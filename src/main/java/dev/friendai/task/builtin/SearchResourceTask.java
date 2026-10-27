package dev.friendai.task.builtin;

import dev.friendai.core.FriendaiServerState;
import dev.friendai.knowledge.search.ResourceSearchProfile;
import dev.friendai.knowledge.search.SearchSession;
import dev.friendai.perception.resource.CaveEntranceObservation;
import dev.friendai.pathing.Pos;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.mining.BlockMatcher;
import dev.friendai.task.obtain.ResourceResolver;
import dev.friendai.task.obtain.ResourceSearchDriver;
import dev.friendai.tool.AccessLevel;
import dev.friendai.tool.Caller;
import java.util.UUID;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Caller-scoped local resource search that uses direct sight and a persisted goal budget. */
public final class SearchResourceTask implements Task {
    private final String resource;
    private final String requestedStrategy;
    private final String scope;
    private final String heading;
    private final Caller caller;
    private Task driver;
    private SearchSession session;
    private String summary = "search has not started";
    private UUID ownerId;

    public SearchResourceTask(String resource, String requestedStrategy, String scope, String heading, Caller caller) {
        this.resource = resource;
        this.requestedStrategy = requestedStrategy == null ? "auto" : requestedStrategy;
        this.scope = scope == null ? "local" : scope;
        this.heading = heading;
        this.caller = caller;
    }

    @Override public String type() { return "search_resource"; }
    @Override public boolean hasTimeout() { return false; }

    @Override public void start(TaskContext context) {
        var owner = context.companion().ownerUuid();
        if (owner == null || caller == null || caller.access().ordinal() < AccessLevel.TRUSTED.ordinal())
            throw new IllegalArgumentException("resource search is unavailable to this caller");
        var serverState = FriendaiServerState.get(context.level().getServer());
        var policy = serverState.config().tasks.resources;
        if (!scope.equals("local") && !scope.equals("expedition"))
            throw new IllegalArgumentException("scope must be local or expedition");
        boolean expedition = scope.equals("expedition");
        if (expedition && (caller.access() != AccessLevel.OWNER || !policy.allowExpeditions))
            throw new IllegalArgumentException("expeditions are disabled or owner-only");
        if (expedition && !serverState.config().companion.chunkLoading.enabled)
            throw new IllegalArgumentException("expeditions require companion chunk loading to be enabled");

        ResourceResolver.Resolution resolved = ResourceResolver.resolve(context.level(), resource);
        if (!resolved.executable() || resolved.blockIds().isEmpty()) throw new IllegalArgumentException(resolved.reason());
        String family = resolved.family();
        ResourceSearchProfile profile = ResourceSearchProfile.builtin(family,
                context.level().dimension().identifier().toString());
        if (profile.strategies().isEmpty()) {
            summary = "no search strategy for " + family + " in " + profile.dimensions();
            return;
        }
        ResourceSearchProfile.Strategy strategy = chooseStrategy(profile);
        if (!profile.allows(context.level().dimension().identifier().toString(), strategy)) {
            summary = "unsupported search strategy " + strategy.name().toLowerCase(java.util.Locale.ROOT)
                    + " for " + profile.family();
            return;
        }
        CaveEntranceObservation caveEntrance = null;
        if (strategy == ResourceSearchProfile.Strategy.CAVE) {
            caveEntrance = serverState.resources().observedCaveEntrances(context.companion()).stream()
                    .findFirst().orElse(null);
            if (caveEntrance == null) {
                summary = "no directly observed cave entrance is available for this search";
                return;
            }
        }
        if (!ready(context)) {
            summary = "not ready for resource search: need food and at least two free inventory slots";
            return;
        }
        if (strategy == ResourceSearchProfile.Strategy.MINE && !miningReady(context, resolved.blockIds())) {
            summary = "not ready for mining search: need a suitable durable pickaxe and 16 torches";
            return;
        }

        int radius = expedition ? policy.expeditionRadius : policy.localRadius;
        int ticks = expedition ? policy.expeditionTicks : policy.localTicks;
        SearchSession.Limits requested = new SearchSession.Limits(radius, radius * 4.0, ticks, 128);
        Pos origin = position(context);
        long logicalTick = context.level().getServer().overworld().getGameTime();
        session = serverState.knowledge().book(owner).searchLedger().acquire(owner,
                context.level().dimension().identifier().toString(), family,
                logicalTick, requested, origin);
        session.clampLimits(new SearchSession.Limits(radius, radius * 4.0, ticks, 128));
        driver = new ResourceSearchDriver(session, profile, resolved.blockIds(), radius, expedition, strategy, heading,
                caveEntrance);
        ownerId = owner;
        driver.start(context);
    }

    @Override public TaskStatus tick(TaskContext context) {
        if (driver == null || session == null) return TaskStatus.FAILED;
        boolean ownerOnline = context.level().getServer().getPlayerList().getPlayer(ownerId) != null;
        if (!ownerId.equals(context.companion().ownerUuid()) || !ownerOnline) {
            stop(context);
            session.interrupt();
            summary = "search interrupted because the owner is unavailable";
            return TaskStatus.FAILED;
        }
        TaskStatus status = driver.tick(context);
        summary = driver.resultSummary();
        return status;
    }

    @Override public void stop(TaskContext context) {
        if (driver != null) driver.stop(context);
        if (session != null && !session.terminal()) session.interrupt();
    }
    @Override public String describe() { return "searching locally for " + resource; }
    @Override public String resultSummary() { return summary; }
    @Override public String saveData() { return session == null ? null : "resource_session:v1:" + session.snapshot().id(); }

    private ResourceSearchProfile.Strategy chooseStrategy(ResourceSearchProfile profile) {
        if (!requestedStrategy.equalsIgnoreCase("auto")) {
            try { return ResourceSearchProfile.Strategy.valueOf(requestedStrategy.toUpperCase(java.util.Locale.ROOT)); }
            catch (IllegalArgumentException e) { throw new IllegalArgumentException("unknown search strategy: " + requestedStrategy); }
        }
        if (profile.strategies().contains(ResourceSearchProfile.Strategy.SURFACE)) return ResourceSearchProfile.Strategy.SURFACE;
        if (profile.strategies().contains(ResourceSearchProfile.Strategy.MINE)) return ResourceSearchProfile.Strategy.MINE;
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

    private static boolean miningReady(TaskContext context, Set<String> blockIds) {
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
                return id != null && BuiltInRegistries.BLOCK.containsKey(id)
                        && stack.getDestroySpeed(BuiltInRegistries.BLOCK.getValue(id).defaultBlockState()) > 1.0F;
            });
            if (remaining >= 160 && speedsTargets) pickaxe = true;
        }
        return pickaxe && torches >= 16;
    }
}
