package dev.friendai.features.trading;

import java.util.UUID;

/** Persistence boundary for owner-scoped trader observations. */
public interface TradeBookRepository {
    TradeBook book(UUID owner);
}
