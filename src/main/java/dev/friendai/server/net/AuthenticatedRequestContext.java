package dev.friendai.server.net;

import dev.friendai.server.runtime.ServerRuntime;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;

/** Actor and request metadata resolved from the current accepted connection, never from the body. */
public record AuthenticatedRequestContext(
        ServerPlayer player, UUID connectionSessionId, long requestId, ServerRuntime runtime) {
    public AuthenticatedRequestContext {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(connectionSessionId, "connectionSessionId");
        Objects.requireNonNull(runtime, "runtime");
        if (requestId <= 0) throw new IllegalArgumentException("requestId must be positive");
        if (runtime.server() != player.level().getServer()) {
            throw new IllegalArgumentException("runtime must belong to the authenticated player's server");
        }
    }
}
