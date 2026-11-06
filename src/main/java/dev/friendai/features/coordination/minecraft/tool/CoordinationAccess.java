package dev.friendai.features.coordination.minecraft.tool;

import dev.friendai.agent.coordination.HandoffRequest;
import dev.friendai.agent.coordination.HelpRequest;
import java.util.Optional;
import java.util.UUID;

/**
 * The server-owned side of the coordination tools: the help and handoff books a running server keeps, plus the one
 * brain hop a repair request needs. The server supplies an implementation per runtime, so the tool adapter never
 * reaches into the runtime itself.
 */
public interface CoordinationAccess {
    boolean requestHelp(HelpRequest request);

    Optional<HelpRequest> acceptHelp(UUID fromOwner, UUID toOwner, long now);

    boolean declineHelp(UUID fromOwner, UUID toOwner);

    boolean offerHandoff(HandoffRequest request);

    Optional<HandoffRequest> takeHandoff(UUID fromOwner, UUID toOwner, long now);

    boolean declineHandoff(UUID fromOwner, UUID toOwner);

    /** Asks the owner's brain to repair a queued plan on the server thread. */
    void postReplan(UUID owner, long planId, String reason);
}
