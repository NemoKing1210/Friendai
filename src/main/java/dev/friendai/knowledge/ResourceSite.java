package dev.friendai.knowledge;

import dev.friendai.pathing.Pos;
import java.util.Objects;
import java.util.Set;

public record ResourceSite(
        long id,
        String family,
        String dimension,
        Set<Pos> members,
        Pos representative,
        Pos approach,
        long firstSeen,
        long lastSeen,
        Availability availability,
        RouteStatus routeStatus,
        boolean approximate) {
    public enum Availability {
        OBSERVED,
        STALE,
        DEPLETED
    }

    public enum RouteStatus {
        UNKNOWN,
        REACHABLE,
        TEMPORARILY_UNREACHABLE
    }

    public ResourceSite {
        if (id < 1) throw new IllegalArgumentException("id must be positive");
        if (family == null || family.isBlank()) throw new IllegalArgumentException("family must not be blank");
        if (dimension == null || dimension.isBlank()) throw new IllegalArgumentException("dimension must not be blank");
        members = Set.copyOf(Objects.requireNonNull(members, "members"));
        Objects.requireNonNull(representative, "representative");
        Objects.requireNonNull(approach, "approach");
        Objects.requireNonNull(availability, "availability");
        Objects.requireNonNull(routeStatus, "routeStatus");
    }
}
