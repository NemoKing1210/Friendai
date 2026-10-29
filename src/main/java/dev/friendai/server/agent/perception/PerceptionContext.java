package dev.friendai.server.agent.perception;

import dev.friendai.agent.autonomy.FreeTimeService;
import dev.friendai.perception.resource.ResourceCandidates;
import dev.friendai.server.configuration.ServerSettings;
import java.util.Objects;

/** Runtime services required to build a perception snapshot, passed by the server adapter. */
public record PerceptionContext(ResourceCandidates resources, ServerSettings settings, FreeTimeService freeTime) {
    public PerceptionContext {
        Objects.requireNonNull(resources);
        Objects.requireNonNull(settings);
        Objects.requireNonNull(freeTime);
    }
}
