package dev.friendai.creative;

import java.util.Objects;
import java.util.UUID;

/** The initiating player and entity-mode generation attached to one creative world-changing job. */
public record CreativeActionContext(UUID principal, long modeRevision) {
    public CreativeActionContext {
        Objects.requireNonNull(principal, "principal");
        if (modeRevision < 0) {
            throw new IllegalArgumentException("modeRevision must not be negative");
        }
    }

    public boolean matches(UUID currentOwner, long currentModeRevision) {
        return principal.equals(currentOwner) && modeRevision == currentModeRevision;
    }
}
