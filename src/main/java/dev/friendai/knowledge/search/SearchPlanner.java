package dev.friendai.knowledge.search;

import dev.friendai.pathing.Pos;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Deterministic frontier ranking over observed or trail-derived candidates only. */
public final class SearchPlanner {
    public enum Heading { NORTH, EAST, SOUTH, WEST }
    public record Frontier(Pos position, Pos approach, ResourceSearchProfile.Strategy strategy,
                           int expectedNewViewpoints, long estimatedTravelTicks) {
        public Frontier {
            Objects.requireNonNull(position); Objects.requireNonNull(approach); Objects.requireNonNull(strategy);
            if (expectedNewViewpoints < 0 || estimatedTravelTicks < 0) throw new IllegalArgumentException("negative frontier estimate");
        }
    }
    public record Request(String dimension, Pos origin, List<Frontier> frontiers, ResourceSearchProfile profile,
                          SearchCoverage coverage, long budgetTicks, long tick, Heading heading) {
        public Request(Pos origin, List<Frontier> frontiers, ResourceSearchProfile profile,
                SearchCoverage coverage, long budgetTicks, long tick) {
            this("minecraft:overworld", origin, frontiers, profile, coverage, budgetTicks, tick, Heading.NORTH);
        }
        public Request {
            if (dimension == null || dimension.isBlank()) throw new IllegalArgumentException("dimension must not be blank");
            Objects.requireNonNull(origin); frontiers = List.copyOf(frontiers); Objects.requireNonNull(profile);
            Objects.requireNonNull(coverage); Objects.requireNonNull(heading);
            if (budgetTicks < 0 || tick < 0) throw new IllegalArgumentException("negative search budget or tick");
        }
    }

    public Optional<Frontier> choose(Request request) {
        return request.frontiers().stream()
                .filter(frontier -> request.profile().allows(request.dimension(), frontier.strategy()))
                .filter(frontier -> frontier.estimatedTravelTicks() <= request.budgetTicks())
                .filter(frontier -> !request.coverage().covered(request.dimension(), frontier.approach(),
                        request.profile().family(), frontier.strategy(), request.tick(), 6_000))
                .filter(frontier -> !blockedCooldown(request, frontier))
                .max(Comparator.comparingDouble((Frontier f) -> score(request, f))
                        .thenComparingInt(f -> headingRank(request, f))
                        .thenComparingInt(f -> -f.approach().x())
                        .thenComparingInt(f -> -f.approach().z())
                        .thenComparingInt(f -> -f.approach().y()));
    }

    private static double score(Request request, Frontier f) {
        long recent = request.coverage().recentVisits(request.dimension(), f.approach(), request.profile().family(), f.strategy(), request.tick(), 6_000);
        long failed = request.coverage().failedAttempts(request.dimension(), f.approach(), request.profile().family(), f.strategy());
        return 8.0 * f.expectedNewViewpoints() - f.estimatedTravelTicks() / 20.0 - 4.0 * recent - 10.0 * failed;
    }

    private static boolean blockedCooldown(Request request, Frontier f) {
        return request.coverage().snapshot().stream().anyMatch(v -> v.dimension().equals(request.dimension())
                && v.viewpoint().equals(f.approach()) && v.family().equals(request.profile().family())
                && v.strategy() == f.strategy() && v.outcome() == SearchCoverage.Outcome.BLOCKED
                && request.tick() >= v.tick() && request.tick() - v.tick() < 6_000);
    }

    private static int headingRank(Request request, Frontier f) {
        int dx = f.approach().x() - request.origin().x();
        int dz = f.approach().z() - request.origin().z();
        Heading direction = Math.abs(dx) > Math.abs(dz) ? (dx > 0 ? Heading.EAST : Heading.WEST)
                : (dz < 0 ? Heading.NORTH : Heading.SOUTH);
        List<Heading> order = switch (request.heading()) {
            case NORTH -> List.of(Heading.NORTH, Heading.EAST, Heading.SOUTH, Heading.WEST);
            case EAST -> List.of(Heading.EAST, Heading.SOUTH, Heading.WEST, Heading.NORTH);
            case SOUTH -> List.of(Heading.SOUTH, Heading.WEST, Heading.NORTH, Heading.EAST);
            case WEST -> List.of(Heading.WEST, Heading.NORTH, Heading.EAST, Heading.SOUTH);
        };
        return -order.indexOf(direction);
    }
}
