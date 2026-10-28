package dev.friendai.features.trading.minecraft;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.CompanionManager;
import dev.friendai.task.builtin.RefreshTradesTask;
import dev.friendai.task.trading.TraderObserver;
import dev.friendai.trading.TradeStore;
import dev.friendai.trading.TraderRecord;
import dev.friendai.trading.TradingRuntimeSettings;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.server.MinecraftServer;

/** Schedules idle offer refreshes for the companions owned by one server. */
public final class TraderRefreshService {

    private final MinecraftServer server;
    private final TraderObserver traderObserver;
    private final TradeStore trading;
    private final Supplier<TradingRuntimeSettings> settings;
    private final Map<UUID, Long> nextTradingRefresh = new HashMap<>();

    public TraderRefreshService(
            MinecraftServer server,
            TraderObserver traderObserver,
            TradeStore trading,
            Supplier<TradingRuntimeSettings> settings) {
        this.server = Objects.requireNonNull(server);
        this.traderObserver = Objects.requireNonNull(traderObserver);
        this.trading = Objects.requireNonNull(trading);
        this.settings = Objects.requireNonNull(settings);
    }

    /** Schedules at most one local offer inspection while an owner's task runner has no work to displace. */
    public void tick() {
        var policy = settings.get();
        if (!policy.enabled() || !policy.idleRefresh()) return;
        long now = server.overworld().getGameTime();
        List<CompanionEntity> companions = CompanionManager.get(server).all();
        for (CompanionEntity companion : companions) {
            UUID owner = companion.ownerUuid();
            if (owner == null || companion.owner().isEmpty()) continue;
            if (nextTradingRefresh.getOrDefault(owner, 0L) > now) continue;
            var runner = companion.executionCoordinator();
            if (runner.hasWork() || runner.paused()) continue;
            List<TraderRecord> nearby = traderObserver.observe(companion);
            var ownerBook = trading.book(owner);
            TraderRecord candidate = nearby.stream()
                    .filter(record -> !"minecraft:piglin".equals(record.entityType()))
                    .map(record -> ownerBook.trader(record.id()).orElse(record))
                    .sorted(Comparator.comparing(
                                    (TraderRecord record) -> record.status() == TraderRecord.Status.VERIFIED)
                            .thenComparingLong(record -> record.offersSeen() < 0 ? -1 : record.offersSeen())
                            .thenComparing(TraderRecord::id))
                    .findFirst()
                    .orElse(null);
            nextTradingRefresh.put(owner, now + policy.idleRefreshIntervalTicks());
            if (candidate != null) {
                runner.start(
                        new RefreshTradesTask(List.of(candidate.id()), now + policy.maxWaitTicks()), "refresh_trades");
                break;
            }
        }
        nextTradingRefresh
                .keySet()
                .removeIf(owner -> companions.stream().noneMatch(companion -> owner.equals(companion.ownerUuid())));
    }
}
