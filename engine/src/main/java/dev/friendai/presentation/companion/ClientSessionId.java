package dev.friendai.presentation.companion;

import java.util.Objects;
import java.util.UUID;

/** Unique identity for one client connection, used to reject callbacks queued by an older connection. */
public record ClientSessionId(UUID value) {
    public ClientSessionId {
        Objects.requireNonNull(value, "value");
    }

    public static ClientSessionId create() {
        return new ClientSessionId(UUID.randomUUID());
    }
}
