package dev.friendai.server.agent.coordination;

import dev.friendai.agent.coordination.CompanionClaim;
import dev.friendai.agent.coordination.HelpRequest;
import dev.friendai.agent.coordination.HelpRequestBook;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.queue.Job;
import dev.friendai.server.runtime.ServerRuntime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Server-thread facade over the derived claims and the bounded help book. It reads the other companions' existing
 * queues; it never executes a tool for another companion and never grants authority.
 */
public final class ServerCoordination {
    private final ServerRuntime runtime;
    private final HelpRequestBook requests = new HelpRequestBook();

    public ServerCoordination(ServerRuntime runtime) {
        this.runtime = java.util.Objects.requireNonNull(runtime);
    }

    /** A live job of another owner that repeats this candidate's tool and target, if any. */
    public Optional<CompanionClaim> duplicate(UUID owner, Job candidate) {
        if (owner == null || candidate == null) return Optional.empty();
        for (CompanionEntity companion : runtime.companions().all()) {
            UUID other = companion.ownerUuid();
            if (other == null || other.equals(owner)) {
                continue;
            }
            Optional<CompanionClaim> claim = CompanionClaim.duplicate(
                    other, companion.executionCoordinator().queue().jobs(), candidate);
            if (claim.isPresent()) {
                return claim;
            }
        }
        return Optional.empty();
    }

    public boolean requestHelp(HelpRequest request) {
        return requests.request(request);
    }

    public Optional<HelpRequest> acceptHelp(UUID fromOwner, UUID toOwner, long now) {
        return requests.accept(fromOwner, toOwner, now);
    }

    public boolean declineHelp(UUID fromOwner, UUID toOwner) {
        return requests.decline(fromOwner, toOwner);
    }

    public List<HelpRequest> openFor(UUID toOwner, long now) {
        return requests.openFor(toOwner, now);
    }

    public int expire(long now) {
        return requests.expire(now);
    }

    public int openCount() {
        return requests.size();
    }

    public long clock() {
        return runtime.server().getTickCount();
    }
}
