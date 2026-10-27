package dev.friendai.trading;

import java.util.Optional;
import java.util.UUID;

/** Explicit stochastic gold budget for one piglin; outcomes are observations, never promises. */
public record BarterOrder(UUID orderId, UUID owner, UUID scopeId, UUID piglin, int maxIngots,
                          Optional<TradeQuery> earlyStopTarget, int targetCount, long deadlineTick) {
    public BarterOrder {
        if (orderId == null || owner == null || scopeId == null || piglin == null
                || maxIngots < 1 || maxIngots > 64 || earlyStopTarget == null
                || targetCount < 0 || deadlineTick < 0
                || (earlyStopTarget.isEmpty() && targetCount != 0)
                || (earlyStopTarget.isPresent() && targetCount < 1)) {
            throw new IllegalArgumentException("invalid bounded barter order");
        }
        if (earlyStopTarget.isPresent() && earlyStopTarget.get().limit() > TradeQuery.MAX_RESULTS) {
            throw new IllegalArgumentException("barter early-stop query is outside supported bounds");
        }
        if (earlyStopTarget.isPresent() && earlyStopTarget.get().direction() != TradeQuery.Direction.BUY) {
            throw new IllegalArgumentException("barter early-stop target must be a collected item");
        }
    }
}
