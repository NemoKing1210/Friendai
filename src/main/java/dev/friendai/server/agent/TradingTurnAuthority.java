package dev.friendai.server.agent;

import dev.friendai.agent.turn.TradingTurnPort;
import dev.friendai.execution.tool.Caller;
import dev.friendai.trading.TradeAuthority;
import java.util.Objects;
import java.util.UUID;

/** Owns the runtime-only trading scopes for one companion session. */
public final class TradingTurnAuthority implements TradingTurnPort {
    private final TradeAuthority authority;

    public TradingTurnAuthority(UUID owner) {
        authority = new TradeAuthority(Objects.requireNonNull(owner));
    }

    @Override
    public Caller begin(Caller caller, boolean playerInitiated) {
        return authority.begin(caller, playerInitiated);
    }

    public TradeAuthority authority() {
        return authority;
    }

    public void invalidateAll(String reason) {
        authority.invalidateAll(reason);
    }
}
