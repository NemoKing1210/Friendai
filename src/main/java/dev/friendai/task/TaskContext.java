package dev.friendai.task;

import dev.friendai.agent.autonomy.FreeTimeService;
import dev.friendai.agent.goal.GoalService;
import dev.friendai.agent.turn.BrainEvent;
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
import dev.friendai.features.mining.MiningRuntimeSettings;
import dev.friendai.features.navigation.pathing.BlockList;
import dev.friendai.features.navigation.pathing.PathingConfig;
import dev.friendai.features.placements.minecraft.PlacedService;
import dev.friendai.features.places.minecraft.PlaceService;
import dev.friendai.features.survival.minecraft.FoodSubtaskFactory;
import dev.friendai.features.trading.TradeAuthority;
import dev.friendai.features.trading.TradeBook;
import dev.friendai.features.trading.minecraft.trading.*;
import dev.friendai.infrastructure.config.ServerConfig;
import dev.friendai.infrastructure.persistence.knowledge.KnowledgeStore;
import dev.friendai.infrastructure.persistence.memory.MemoryStore;
import dev.friendai.infrastructure.persistence.trading.TradeStore;
import dev.friendai.perception.resource.ObservationScheduler;
import dev.friendai.perception.resource.ResourceCandidates;
import dev.friendai.server.agent.CompanionSessions;
import dev.friendai.server.configuration.ServerSettings;
import dev.friendai.server.creative.CreativeModeService;
import dev.friendai.server.execution.ExecutionCoordinator;
import dev.friendai.server.execution.tools.MinecraftFoodSubtaskFactory;
import dev.friendai.server.instructions.SkillService;
import dev.friendai.server.memory.TrustConfirmations;
import dev.friendai.server.runtime.ServerRuntime;
import dev.friendai.server.runtime.ServerRuntimeRegistry;
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

    public boolean tradingEnabled() {
        return serverSettings.trading().enabled();
    }

    public PathingConfig pathingConfig() {
        return serverSettings.pathing();
    }

    /** Whether a valuable drop is protected before its block is broken, and what that may cost. */
    public MiningRuntimeSettings mining() {
        return serverSettings.mining();
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
        return runtime().resourceCandidates();
    }

    public KnowledgeStore knowledge() {
        return runtime().knowledge();
    }
    /** The companion owner's knowledge book without exposing the persistence store to a feature task. */
    public ResourceKnowledge ownerKnowledge() {
        UUID owner = companion.ownerUuid();
        if (owner == null) throw new IllegalStateException("owner knowledge is unavailable without an owner");
        return runtime().knowledge().book(owner);
    }

    public SearchHintStore searchHints() {
        return runtime().searchHints();
    }

    public ObservationScheduler observations() {
        return runtime().observations();
    }

    public FreeTimeService freeTime() {
        return runtime().freeTime();
    }

    public CompanionSessions sessions() {
        return runtime().sessions();
    }
    /** Trading authority for an owner, without exposing the session service to feature adapters. */
    public TradeAuthority tradeAuthorityFor(UUID owner) {
        return runtime().sessions().tradeAuthorityFor(owner);
    }

    public void postWorldNotice(UUID owner, String message) {
        runtime().sessions().post(owner, new BrainEvent.WorldNotice(message));
    }

    /** Sets the owner's respawn point from a sleep task without exposing the companion-life store. */
    public void setRespawnPoint(UUID owner, CompanionLifeData.RespawnPoint point) {
        runtime().companionLife().setRespawnPoint(owner, point);
    }

    public ExpeditionChunkHorizon.Lease requestExpeditionChunk(ChunkPos chunk) {
        UUID owner = companion.ownerUuid();
        if (owner == null) throw new IllegalStateException("chunk lease is unavailable without an owner");
        return runtime().chunkLoader().expeditionHorizon().request(owner, level, chunk);
    }

    public ServerConfig config() {
        return runtime().config();
    }
    /** Feature-owned place operations; the service holds only the owner-book and settings providers. */
    public PlaceService placeService() {
        return runtime().placeService();
    }
    /** Search-specific policy values, detached from the server configuration representation. */
    public ResourceSearchSettings resourceSearchSettings() {
        var config = runtime().config();
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

    /** Feature-owned placed-ledger operations, backed by this server's owner books. */
    public PlacedService placedService() {
        return runtime().placedService();
    }

    public TradeStore trading() {
        return runtime().trading();
    }
    /** The owner's observed trades without exposing the persistence store to a feature adapter. */
    public TradeBook tradeBook(UUID owner) {
        return runtime().trading().book(owner);
    }

    public MerchantLeases merchantLeases() {
        return runtime().merchantLeases();
    }

    public TradeSessionGuard tradeSessionGuard() {
        return runtime().tradeSessionGuard();
    }

    public TraderObserver traderObserver() {
        return runtime().traderObserver();
    }

    public BarterDropTracker barterDropTracker() {
        return runtime().barterDropTracker();
    }

    public PiglinBarterAdapter piglinBarterAdapter() {
        return runtime().piglinBarterAdapter();
    }

    public MerchantAdapter merchantAdapter() {
        return runtime().merchantAdapter();
    }

    public CompanionLifeData companionLife() {
        return runtime().companionLife();
    }

    public MemoryStore memory() {
        return runtime().memory();
    }

    public MemoryViewers memoryViewers() {
        return runtime().memoryViewers();
    }

    public GoalService goals() {
        return runtime().goals();
    }

    public SkillService skills() {
        return runtime().skills();
    }

    public TrustConfirmations trust() {
        return runtime().trust();
    }

    private ServerRuntime runtime() {
        return ServerRuntimeRegistry.getOrCreate(level.getServer());
    }
}
