package dev.friendai.features.acquisition;

import dev.friendai.features.trading.TradeBudget;
import dev.friendai.features.trading.TraderRecord;
import java.util.List;
import java.util.UUID;

/** Explicit, owner-issued authority supplied to obtain planning; null means trading is disabled. */
public record TradingContext(
        UUID scopeId, List<TraderRecord> traders, TradeBudget budget, UUID pricingActor, long nowTick) {
    public TradingContext(UUID scopeId, List<TraderRecord> traders, TradeBudget budget) {
        this(scopeId, traders, budget, null, 0);
    }

    public TradingContext {
        if (scopeId == null || traders == null || budget == null || traders.size() > 256 || nowTick < 0) {
            throw new IllegalArgumentException("invalid trading context");
        }
        traders = List.copyOf(traders);
    }
}
