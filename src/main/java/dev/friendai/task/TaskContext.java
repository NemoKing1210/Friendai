package dev.friendai.task;

import dev.friendai.agent.autonomy.FreeTimeService;
import dev.friendai.agent.goal.GoalService;
import dev.friendai.agent.turn.BrainEvent;
import dev.friendai.core.CreativeModeService;
import dev.friendai.core.FriendaiServerState;
import dev.friendai.core.SkillService;
import dev.friendai.core.TrustConfirmations;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.CompanionLifeData;
import dev.friendai.entity.ExpeditionChunkHorizon;
import dev.friendai.execution.settings.TaskRuntimeSettings;
import dev.friendai.features.acquisition.ResourceSearchSettings;
import dev.friendai.features.creative.CreativeAction;
import dev.friendai.features.creative.CreativeActionContext;
import dev.friendai.features.knowledge.ResourceKnowledge;
import dev.friendai.features.knowledge.search.SearchHintStore;
import dev.friendai.features.memory.MemoryViewers;
import dev.friendai.features.navigation.pathing.BlockList;
import dev.friendai.features.navigation.pathing.PathingConfig;
import dev.friendai.features.placements.PlacedBook;
import dev.friendai.features.places.PlaceRegistry;
import dev.friendai.features.places.PlaceSettings;
import dev.friendai.features.survival.minecraft.FoodSubtaskFactory;
import dev.friendai.features.trading.TradeAuthority;
import dev.friendai.features.trading.TradeBook;
import dev.friendai.features.trading.minecraft.trading.*;
import dev.friendai.infrastructure.config.ServerConfig;
import dev.friendai.infrastructure.persistence.knowledge.KnowledgeStore;
import dev.friendai.infrastructure.persistence.memory.MemoryStore;
import dev.friendai.infrastructure.persistence.placements.PlacedStore;
import dev.friendai.infrastructure.persistence.places.PlaceStore;
import dev.friendai.infrastructure.persistence.trading.TradeStore;
import dev.friendai.perception.resource.ObservationScheduler;
import dev.friendai.perception.resource.ResourceCandidates;
import dev.friendai.server.agent.CompanionSessions;
import dev.friendai.server.configuration.ServerSettings;
import dev.friendai.server.execution.ExecutionCoordinator;
import dev.friendai.server.execution.tools.MinecraftFoodSubtaskFactory;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

public record TaskContext(
        CompanionEntity companion,
        ServerLevel level,
        ExecutionCoordinator execution,
        CreativeActionContext creativeActionContext,
        ServerSettings serverSettings) {
    private static final FoodSubtaskFactory FOOD_SUBTASKS = new MinecraftFoodSubtaskFactory();

    public TaskContext(
            CompanionEntity companion,
            ServerLevel level,
            ExecutionCoordinator execution,
            CreativeActionContext creativeActionContext) {
        this(companion, level, execution, creativeActionContext, companion.serverSettings());
    }

    public TaskContext(CompanionEntity companion, ServerLevel level, ExecutionCoordinator execution) {
        this(companion, level, execution, null, companion.serverSettings());
    }
    /** The context of a task it ticks itself, for tests and for tasks that run others. */
    public TaskContext(CompanionEntity companion, ServerLevel level) {
        this(companion, level, companion.executionCoordinator(), null, companion.serverSettings());
    }

    public TaskRuntimeSettings settings() {
        return serverSettings.tasks();
    }

    public PathingConfig pathingConfig() {
        return serverSettings.pathing();
    }

    public BlockList breakList() {
        return serverSettings.breakList();
    }

    public BlockList placeList() {
        return serverSettings.placeList();
    }

    public int autoMineMinBlocks() {
        return serverSettings.autoMineMinBlocks();
    }

    public int foodLevel() {
        return companion.hunger().food();
    }

    public FoodSubtaskFactory foodSubtasks() {
        return FOOD_SUBTASKS;
    }

    public void addBlockExhaustion() {
        companion.hunger().addExhaustion(CompanionEntity.EXHAUSTION_BLOCK);
    }

    public List<String> keepItems() {
        return serverSettings.autonomy().keepItems();
    }

    public Optional<ServerPlayer> owner() {
        return companion.owner();
    }

    public boolean canUseCreativeAction(CreativeAction action) {
        return CreativeModeService.isActionAuthorized(companion, creativeActionContext, action);
    }

    public boolean flightAuthorized() {
        return canUseCreativeAction(CreativeAction.FLIGHT);
    }

    public boolean passiveFlightAllowed() {
        return CreativeModeService.ownerMayUsePassiveFlight(companion);
    }

    public boolean defaultActivity() {
        return execution != null && execution.currentIsDefaultActivity();
    }

    public void debug(String text) {
        if (execution != null) execution.debug(text);
    }
    /** Posts a task progress notice through the active execution boundary. */
    public void notice(String text) {
        if (execution != null) execution.notice(text);
    }
    /** The running task belongs to free time, which follows stricter rules than an owner order. */
    public boolean autonomous() {
        return execution != null && execution.autonomousActive();
    }
    /** Owner-scoped resource evidence for world tasks. The server state itself is never exposed to features. */
    public ResourceCandidates resources() {
        return state().resources();
    }

    public KnowledgeStore knowledge() {
        return state().knowledge();
    }
    /** The companion owner's knowledge book without exposing the persistence store to a feature task. */
    public ResourceKnowledge ownerKnowledge() {
        UUID owner = companion.ownerUuid();
        if (owner == null) throw new IllegalStateException("owner knowledge is unavailable without an owner");
        return state().knowledge().book(owner);
    }

    public SearchHintStore searchHints() {
        return state().searchHints();
    }

    public ObservationScheduler observations() {
        return state().observations();
    }

    public FreeTimeService freeTime() {
        return state().freeTime();
    }

    public CompanionSessions sessions() {
        return state().sessions();
    }
    /** Trading authority for an owner, without exposing the session service to feature adapters. */
    public TradeAuthority tradeAuthorityFor(UUID owner) {
        return state().sessions().tradeAuthorityFor(owner);
    }

    public void postWorldNotice(UUID owner, String message) {
        state().sessions().post(owner, new BrainEvent.WorldNotice(message));
    }

    public ExpeditionChunkHorizon.Lease requestExpeditionChunk(ChunkPos chunk) {
        UUID owner = companion.ownerUuid();
        if (owner == null) throw new IllegalStateException("chunk lease is unavailable without an owner");
        return state().chunkLoader().expeditionHorizon().request(owner, level, chunk);
    }

    public ServerConfig config() {
        return state().config();
    }
    /** Place feature settings without exposing the server configuration representation. */
    public PlaceSettings placeSettings() {
        var places = state().config().places;
        return new PlaceSettings(
                places.enabled,
                places.autoRemember,
                places.maxPlaces,
                places.mergeRadius,
                places.promptLimit,
                places.promptBudget);
    }

    public PlaceStore places() {
        return state().places();
    }
    /** The companion owner's place book without exposing the persistence store to a feature task. */
    public PlaceRegistry placeBook(UUID owner) {
        return state().places().book(owner);
    }
    /** Search-specific policy values, detached from the server configuration representation. */
    public ResourceSearchSettings resourceSearchSettings() {
        var config = state().config();
        var resources = config.tasks.resources;
        return new ResourceSearchSettings(
                resources.allowExpeditions,
                config.companion.chunkLoading.enabled,
                resources.localRadius,
                resources.localTicks,
                resources.expeditionRadius,
                resources.expeditionTicks,
                resources.escort());
    }

    public PlacedStore placed() {
        return state().placed();
    }
    /** The owner's placement ledger without exposing the persistence store to a domain service. */
    public PlacedBook placedBook(UUID owner) {
        return state().placed().book(owner);
    }

    public TradeStore trading() {
        return state().trading();
    }
    /** The owner's observed trades without exposing the persistence store to a feature adapter. */
    public TradeBook tradeBook(UUID owner) {
        return state().trading().book(owner);
    }

    public MerchantLeases merchantLeases() {
        return state().merchantLeases();
    }

    public TradeSessionGuard tradeSessionGuard() {
        return state().tradeSessionGuard();
    }

    public TraderObserver traderObserver() {
        return state().traderObserver();
    }

    public BarterDropTracker barterDropTracker() {
        return state().barterDropTracker();
    }

    public PiglinBarterAdapter piglinBarterAdapter() {
        return state().piglinBarterAdapter();
    }

    public MerchantAdapter merchantAdapter() {
        return state().merchantAdapter();
    }

    public CompanionLifeData companionLife() {
        return state().companionLife();
    }

    public MemoryStore memory() {
        return state().memory();
    }

    public MemoryViewers memoryViewers() {
        return state().memoryViewers();
    }

    public GoalService goals() {
        return state().goals();
    }

    public SkillService skills() {
        return state().skills();
    }

    public TrustConfirmations trust() {
        return state().trust();
    }

    private FriendaiServerState state() {
        return FriendaiServerState.get(level.getServer());
    }
}
