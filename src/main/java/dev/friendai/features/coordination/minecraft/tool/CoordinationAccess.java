package dev.friendai.features.coordination.minecraft.tool;

import dev.friendai.agent.coordination.HandoffRequest;
import dev.friendai.agent.coordination.HelpRequest;
import dev.friendai.entity.CompanionEntity;
import java.util.Optional;
import java.util.UUID;

/**
 * What the coordination tools act on: the server-owned help and handoff books, the companion of an owner, the plan
 * repair channel and the online owners those requests name. The tools never reach into the server runtime; the live
 * implementation is a server adapter, and the default one refuses every call.
 */
public interface CoordinationAccess {
    /** An owner who is online right now, named the way the model named them. */
    record OnlineOwner(UUID owner, String name) {}

    /** No books and no runtime: every call reports that nothing is open. */
    CoordinationAccess UNAVAILABLE = new CoordinationAccess() {
        @Override
        public boolean available() {
            return false;
        }

        @Override
        public Optional<OnlineOwner> onlineOwner(String name) {
            return Optional.empty();
        }

        @Override
        public boolean requestHelp(HelpRequest request) {
            return false;
        }

        @Override
        public Optional<HelpRequest> acceptHelp(UUID fromOwner, UUID toOwner, long now) {
            return Optional.empty();
        }

        @Override
        public boolean declineHelp(UUID fromOwner, UUID toOwner) {
            return false;
        }

        @Override
        public boolean offerHandoff(HandoffRequest request) {
            return false;
        }

        @Override
        public Optional<HandoffRequest> takeHandoff(UUID fromOwner, UUID toOwner, long now) {
            return Optional.empty();
        }

        @Override
        public Optional<CompanionEntity> companion(UUID owner) {
            return Optional.empty();
        }

        @Override
        public void requestReplan(UUID owner, long planId, String reason) {}
    };

    /** Whether the live books exist on this server; false makes every tool report an unavailable runtime. */
    boolean available();

    Optional<OnlineOwner> onlineOwner(String name);

    boolean requestHelp(HelpRequest request);

    Optional<HelpRequest> acceptHelp(UUID fromOwner, UUID toOwner, long now);

    boolean declineHelp(UUID fromOwner, UUID toOwner);

    boolean offerHandoff(HandoffRequest request);

    Optional<HandoffRequest> takeHandoff(UUID fromOwner, UUID toOwner, long now);

    Optional<CompanionEntity> companion(UUID owner);

    /** Posts a repair request for one queued plan to its owner's brain. */
    void requestReplan(UUID owner, long planId, String reason);
}
