package dev.friendai.agent.turn;

import dev.friendai.execution.tool.Caller;

/** Server boundary for creating one ephemeral spending scope from an owner-origin player turn. */
@FunctionalInterface
public interface TradingTurnPort {
    TradingTurnPort NOOP = (caller, playerInitiated) -> caller.withTradingScope(null);

    /** Returns the turn caller with its server-issued scope, if this batch may authorize spending. */
    Caller begin(Caller caller, boolean playerInitiated);
}
