package dev.friendai.knowledge;

import dev.friendai.pathing.Pos;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class ResourceQuery {
    private ResourceQuery() {}

    public record Request(Set<String> blockIds, String dimension, Pos origin, double maxDistance, int limit,
                          boolean includeStale) {
        public Request {
            blockIds = Set.copyOf(Objects.requireNonNull(blockIds, "blockIds"));
            if (dimension == null || dimension.isBlank()) throw new IllegalArgumentException("dimension must not be blank");
            Objects.requireNonNull(origin, "origin");
            if (!Double.isFinite(maxDistance) || maxDistance < 0) throw new IllegalArgumentException("maxDistance must be finite and nonnegative");
            if (limit < 1 || limit > 20) throw new IllegalArgumentException("limit must be in 1..20");
        }
    }
    public record Result(ResourceSite site, double distanceBlocks, int lastObservedBlocks, long ageTicks) {}

    public static List<Result> find(ResourceKnowledge book, Request request) {
        return find(book, request, book.logicalTick());
    }

    public static List<Result> find(ResourceKnowledge book, Request request, long now) {
        Objects.requireNonNull(book, "book");
        Objects.requireNonNull(request, "request");
        if (now < 0) throw new IllegalArgumentException("now must be nonnegative");
        double maxDistanceSquared = request.maxDistance() * request.maxDistance();
        return book.querySites(request.blockIds(), request.dimension(), now).stream()
                .map(site -> {
                    double dx = (double) site.representative().x() - request.origin().x();
                    double dy = (double) site.representative().y() - request.origin().y();
                    double dz = (double) site.representative().z() - request.origin().z();
                    double distanceSquared = dx * dx + dy * dy + dz * dz;
                    long age = Math.max(0, Math.max(book.logicalTick(), now) - site.lastSeen());
                    return distanceSquared <= maxDistanceSquared
                            ? new Result(site, Math.sqrt(distanceSquared), book.historicalBlockCount(site.id()), age)
                            : null;
                })
                .filter(Objects::nonNull)
                .filter(result -> request.includeStale() || result.site().availability() != ResourceSite.Availability.STALE)
                .sorted(Comparator.comparingDouble(Result::distanceBlocks).thenComparingLong(result -> result.site().id()))
                .limit(request.limit())
                .toList();
    }
}
