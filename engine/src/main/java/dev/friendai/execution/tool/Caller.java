package dev.friendai.execution.tool;

import java.util.UUID;

/** Who the current turn acts for. Names are labels only; rights are decided by {@link #access()}. */
public record Caller(UUID uuid, String name, AccessLevel access, UUID tradingScope) {
    public Caller(UUID uuid, String name, AccessLevel access) {
        this(uuid, name, access, null);
    }

    public Caller withTradingScope(UUID scope) {
        return new Caller(uuid, name, access, scope);
    }
}
