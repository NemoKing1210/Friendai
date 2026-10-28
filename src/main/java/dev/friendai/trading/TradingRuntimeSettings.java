package dev.friendai.trading;

/** Immutable trading policy copied from the server config and shared with server-side feature adapters. */
public record TradingRuntimeSettings(
        boolean enabled,
        boolean idleRefresh,
        int observationRadius,
        int quoteFreshTicks,
        int locationFreshTicks,
        int idleRefreshIntervalTicks,
        int waitTicks,
        int maxWaitTicks,
        int maxBarterIngots,
        int barterDefaultIngots) {

    public static final TradingRuntimeSettings DEFAULTS =
            new TradingRuntimeSettings(true, true, 64, 1_200, 12_000, 200, 2_400, 12_000, 64, 8);
}
