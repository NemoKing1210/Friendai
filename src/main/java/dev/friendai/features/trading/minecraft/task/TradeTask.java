package dev.friendai.features.trading.minecraft.task;

import dev.friendai.core.FriendaiServerState;
import dev.friendai.features.navigation.minecraft.nav.Navigator;
import dev.friendai.features.trading.TradeAuthority;
import dev.friendai.features.trading.TradeOffer;
import dev.friendai.features.trading.TradeOrder;
import dev.friendai.features.trading.TradeProgress;
import dev.friendai.features.trading.TraderRecord;
import dev.friendai.features.trading.minecraft.trading.MerchantSession;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.trading.Merchant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Executes one live vanilla fill per task tick for the immutable order supplied at creation. */
public final class TradeTask implements Task {
    private static final Logger LOGGER = LoggerFactory.getLogger("friendai/trading");
    private final TradeOrder order;
    private UUID ownerAtStart;
    private String dimensionAtStart = "";
    private Navigator navigator;
    private TradeAuthority authority;
    private TradeOffer quote;
    private TraderRecord traderRecord;
    private TradeProgress progress;
    private TaskStatus terminal;
    private String reason = "";
    private int ticks;

    public TradeTask(TradeOrder order) {
        this.order = java.util.Objects.requireNonNull(order, "trade order is required");
    }

    public TradeOrder order() {
        return order;
    }

    @Override
    public String type() {
        return "trade_item";
    }

    @Override
    public void start(TaskContext context) {
        ownerAtStart = context.companion().ownerUuid();
        dimensionAtStart = context.level().dimension().identifier().toString();
        var state = FriendaiServerState.get(context.level().getServer());
        if (ownerAtStart != null && ownerAtStart.equals(order.owner())) {
            authority = state.tradeAuthorityFor(ownerAtStart);
            progress = currentProgress();
            traderRecord = state.tradeBook(order.owner()).trader(order.trader()).orElse(null);
            if (traderRecord != null) {
                quote = traderRecord.offers().stream()
                        .filter(value -> value.ref().equals(order.offerRef()))
                        .findFirst()
                        .orElse(null);
            }
        }
        navigator = TraderTaskNavigation.navigator(context);
        terminal = null;
        reason = "";
        ticks = 0;
    }

    @Override
    public TaskStatus tick(TaskContext context) {
        if (terminal != null) return terminal;
        try {
            return tickActive(context);
        } catch (RuntimeException failure) {
            String message = "trade task failed: " + safeMessage(failure);
            try {
                return shortResult(context, message);
            } catch (RuntimeException cleanupFailure) {
                LOGGER.warn("Could not reconcile trade task {} after a tick failure", order.orderId(), cleanupFailure);
                try {
                    stop(context);
                } catch (RuntimeException stopFailure) {
                    LOGGER.warn("Could not stop trade task {} after a tick failure", order.orderId(), stopFailure);
                }
                if (terminal != null) return terminal;
                return finish(context, TaskStatus.FAILED, message);
            }
        }
    }

    private TaskStatus tickActive(TaskContext context) {
        if (authority == null)
            return finish(context, TaskStatus.FAILED, "trade order owner does not match the companion");
        if (++ticks > TraderTaskNavigation.MAX_TASK_TICKS) return shortResult(context, "trade task deadline expired");
        String invalid = TraderTaskNavigation.invalidContext(context, ownerAtStart, dimensionAtStart);
        if (invalid != null) return shortResult(context, invalid);
        if (!order.owner().equals(ownerAtStart)) return shortResult(context, "trade order belongs to another owner");
        long nowTick = context.level().getServer().overworld().getGameTime();
        if (nowTick > order.deadlineTick()) return shortResult(context, "trade order deadline expired");

        progress = currentProgress();
        if (progress.terminal()) {
            return finish(
                    context,
                    progress.state() == TradeProgress.State.COMPLETED || progress.state() == TradeProgress.State.PARTIAL
                            ? TaskStatus.SUCCESS
                            : TaskStatus.FAILED,
                    progress.reason());
        }

        if (traderRecord != null && !dimensionAtStart.equals(traderRecord.dimension())) {
            return shortResult(context, "authorized merchant quote belongs to another dimension");
        }
        Entity target = context.level().getEntity(order.trader());
        if (target != null && !target.isAlive()) return shortResult(context, "exact merchant is dead");
        if (target != null && !(target instanceof Merchant)) {
            return shortResult(context, "bound UUID no longer identifies a merchant");
        }
        if (target == null) {
            var state = FriendaiServerState.get(context.level().getServer());
            traderRecord = state.tradeBook(order.owner()).trader(order.trader()).orElse(traderRecord);
            BlockPos hint = TraderTaskNavigation.lastKnownPosition(traderRecord, dimensionAtStart);
            if (hint == null) return shortResult(context, "exact merchant is not loaded in this dimension");
            if (!TraderTaskNavigation.inBoundedArea(context.companion(), hint)) {
                return shortResult(context, "exact merchant is outside the 64-block search bound");
            }
            TraderTaskNavigation.aimAt(navigator, hint, context.companion());
            if (TraderTaskNavigation.atHint(context.companion(), hint)) {
                TraderTaskNavigation.stop(navigator, context);
                return TaskStatus.RUNNING;
            }
            if (navigator.tick(context) == Navigator.State.UNREACHABLE) {
                return shortResult(context, "no safe path to the exact merchant's last location");
            }
            return TaskStatus.RUNNING;
        }
        if (!TraderTaskNavigation.inBoundedArea(context.companion(), target)) {
            return shortResult(context, "exact merchant is outside the 64-block search bound");
        }
        TraderTaskNavigation.aimAt(navigator, target, context.companion());
        if (!TraderTaskNavigation.inActionReach(context.companion(), target)) {
            if (navigator.tick(context) == Navigator.State.UNREACHABLE) {
                return shortResult(context, "no safe path to the exact merchant");
            }
            return TaskStatus.RUNNING;
        }

        MerchantSession.Outcome outcome;
        try (MerchantSession session = FriendaiServerState.get(context.level().getServer())
                .merchantAdapter()
                .openSession(context.companion(), target, order, authority, nowTick)) {
            outcome = session.tradeOnce(order);
        } catch (RuntimeException failure) {
            outcome = new MerchantSession.Outcome(false, safeMessage(failure), currentProgress());
        }
        progress = outcome.progress();
        if (progress.state() == TradeProgress.State.UNCERTAIN) {
            return finish(context, TaskStatus.FAILED, nonBlank(progress.reason(), outcome.reason()));
        }
        if (progress.state() == TradeProgress.State.COMPLETED) {
            return finish(context, TaskStatus.SUCCESS, "");
        }
        if (outcome.traded()) {
            // The menu and its lease are closed by tradeOnce before the runner can yield this tick.
            return TaskStatus.RUNNING;
        }
        return shortResult(context, nonBlank(outcome.reason(), "merchant denied the next fill"));
    }

    private TaskStatus shortResult(TaskContext context, String why) {
        progress = currentProgress();
        if (progress.state() == TradeProgress.State.UNCERTAIN) {
            return finish(context, TaskStatus.FAILED, nonBlank(progress.reason(), why));
        }
        if (progress.state() == TradeProgress.State.COMPLETED) return finish(context, TaskStatus.SUCCESS, "");
        int received = exactReceivedCount(progress);
        if (received > 0 && order.allowPartial()) {
            TradeProgress partial = new TradeProgress(
                    order.orderId(),
                    TradeProgress.State.PARTIAL,
                    progress.spent(),
                    progress.received(),
                    progress.completedTrades(),
                    bounded("remaining=" + remaining(received) + "; " + why));
            authority.record(partial);
            persistProgress(context, partial);
            progress = partial;
            return finish(context, TaskStatus.SUCCESS, why);
        }
        if (progress.state() == TradeProgress.State.AUTHORIZED || progress.state() == TradeProgress.State.RUNNING) {
            TradeProgress failed = new TradeProgress(
                    order.orderId(),
                    TradeProgress.State.FAILED,
                    progress.spent(),
                    progress.received(),
                    progress.completedTrades(),
                    bounded(why));
            authority.record(failed);
            if (progress.completedTrades() > 0) persistProgress(context, failed);
            progress = failed;
        }
        return finish(context, TaskStatus.FAILED, why);
    }

    private TradeProgress currentProgress() {
        return authority == null
                ? emptyProgress()
                : authority.progress(order.orderId()).orElseGet(this::emptyProgress);
    }

    private TradeProgress emptyProgress() {
        return new TradeProgress(order.orderId(), TradeProgress.State.AUTHORIZED, Map.of(), List.of(), 0, "");
    }

    private int exactReceivedCount(TradeProgress value) {
        if (quote == null) return 0;
        return value.receivedCount(quote.output().itemId(), quote.output().componentsJson());
    }

    private int remaining(int received) {
        return Math.max(0, order.desiredOutputCount() - received);
    }

    private TaskStatus finish(TaskContext context, TaskStatus status, String message) {
        terminal = status;
        reason = message == null ? "" : bounded(message);
        try {
            TraderTaskNavigation.stop(navigator, context);
        } catch (RuntimeException cleanupFailure) {
            context.companion().getNavigation().stop();
            context.companion().setPathing(false);
        }
        navigator = null;
        return status;
    }

    @Override
    public void stop(TaskContext context) {
        try {
            TraderTaskNavigation.stop(navigator, context);
        } catch (RuntimeException cleanupFailure) {
            context.companion().getNavigation().stop();
            context.companion().setPathing(false);
        }
        navigator = null;
        if (terminal == null) {
            progress = currentProgress();
            int received = exactReceivedCount(progress);
            if ((progress.state() == TradeProgress.State.AUTHORIZED || progress.state() == TradeProgress.State.RUNNING)
                    && authority != null) {
                TradeProgress.State state = received > 0 && order.allowPartial()
                        ? TradeProgress.State.PARTIAL
                        : TradeProgress.State.INTERRUPTED;
                TradeProgress stopped = new TradeProgress(
                        order.orderId(),
                        state,
                        progress.spent(),
                        progress.received(),
                        progress.completedTrades(),
                        bounded(
                                received > 0
                                        ? "stopped after receiving " + received + " output items"
                                        : "stopped before a payment"));
                authority.record(stopped);
                persistProgress(context, stopped);
                progress = stopped;
            }
            terminal =
                    progress.state() == TradeProgress.State.PARTIAL || progress.state() == TradeProgress.State.COMPLETED
                            ? TaskStatus.SUCCESS
                            : TaskStatus.FAILED;
            reason = progress.reason().isBlank() ? "trade task stopped" : progress.reason();
        }
    }

    @Override
    public String describe() {
        return "trade with merchant " + order.trader();
    }

    @Override
    public String resultSummary() {
        TradeProgress value = progress == null ? currentProgress() : progress;
        int received = exactReceivedCount(value);
        String output = quote == null ? "selected output" : quote.output().itemId();
        String summary =
                "order=" + order.orderId() + "; state=" + value.state().name().toLowerCase(java.util.Locale.ROOT)
                        + "; received=" + received + "/" + order.desiredOutputCount() + " " + output
                        + "; remaining=" + remaining(received) + "; spent=" + value.spent()
                        + (reason.isBlank()
                                ? (value.reason().isBlank() ? "" : "; reason=" + value.reason())
                                : "; reason=" + reason)
                        + "; ticks=" + ticks;
        return summary.length() <= 600 ? summary : summary.substring(0, 597) + "...";
    }

    /** Orders carry a live runtime capability and are never restored from saved task text. */
    @Override
    public String saveData() {
        return null;
    }

    private static String safeMessage(RuntimeException failure) {
        return bounded(failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
    }

    private static String nonBlank(String candidate, String fallback) {
        return candidate == null || candidate.isBlank() ? fallback : candidate;
    }

    private static String bounded(String value) {
        if (value == null || value.isBlank()) return "trade stopped";
        return value.length() <= 512 ? value : value.substring(0, 509) + "...";
    }

    private void persistProgress(TaskContext context, TradeProgress value) {
        try {
            FriendaiServerState.get(context.level().getServer())
                    .tradeBook(order.owner())
                    .record(value);
        } catch (RuntimeException failure) {
            // Runtime authority remains authoritative if informational history cannot be updated.
            LOGGER.warn(
                    "Could not persist final progress for trade order {}: {}", order.orderId(), safeMessage(failure));
        }
    }
}
