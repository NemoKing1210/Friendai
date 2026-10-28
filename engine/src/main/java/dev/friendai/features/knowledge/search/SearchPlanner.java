package dev.friendai.features.knowledge.search;

import dev.friendai.features.navigation.pathing.Pos;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Deterministic frontier ranking over observed or trail-derived candidates only. */
public final class SearchPlanner {
    public enum Heading {
        NORTH,
        EAST,
        SOUTH,
        WEST
    }

    /**
     * Terrain facts for one candidate, sampled by the adapter. A biome edge is the cheapest place to learn
     * something new; water and lava/cliffs are penalties because a leg through them costs more than it returns.
     */
    public record Terrain(
            boolean biomeTransition, double waterFraction, double hazardFraction, boolean onTrail, String biomeId) {
        public static final Terrain NEUTRAL = new Terrain(false, 0, 0, false, "");

        public Terrain {
            if (!Double.isFinite(waterFraction) || waterFraction < 0 || waterFraction > 1)
                throw new IllegalArgumentException("waterFraction must be between 0 and 1");
            if (!Double.isFinite(hazardFraction) || hazardFraction < 0 || hazardFraction > 1)
                throw new IllegalArgumentException("hazardFraction must be between 0 and 1");
            biomeId = biomeId == null ? "" : biomeId;
        }
    }

    public record Frontier(
            Pos position,
            Pos approach,
            ResourceSearchProfile.Strategy strategy,
            int expectedNewViewpoints,
            long estimatedTravelTicks,
            Terrain terrain) {
        public Frontier(
                Pos position,
                Pos approach,
                ResourceSearchProfile.Strategy strategy,
                int expectedNewViewpoints,
                long estimatedTravelTicks) {
            this(position, approach, strategy, expectedNewViewpoints, estimatedTravelTicks, Terrain.NEUTRAL);
        }

        public Frontier {
            Objects.requireNonNull(position);
            Objects.requireNonNull(approach);
            Objects.requireNonNull(strategy);
            Objects.requireNonNull(terrain);
            if (expectedNewViewpoints < 0 || estimatedTravelTicks < 0)
                throw new IllegalArgumentException("negative frontier estimate");
        }
    }

    public record Request(
            String dimension,
            Pos origin,
            List<Frontier> frontiers,
            ResourceSearchProfile profile,
            SearchCoverage coverage,
            long budgetTicks,
            long tick,
            Heading heading,
            Optional<SearchHint> hint) {
        public Request(
                Pos origin,
                List<Frontier> frontiers,
                ResourceSearchProfile profile,
                SearchCoverage coverage,
                long budgetTicks,
                long tick) {
            this(
                    "minecraft:overworld",
                    origin,
                    frontiers,
                    profile,
                    coverage,
                    budgetTicks,
                    tick,
                    Heading.NORTH,
                    Optional.empty());
        }

        public Request(
                String dimension,
                Pos origin,
                List<Frontier> frontiers,
                ResourceSearchProfile profile,
                SearchCoverage coverage,
                long budgetTicks,
                long tick,
                Heading heading) {
            this(dimension, origin, frontiers, profile, coverage, budgetTicks, tick, heading, Optional.empty());
        }

        public Request {
            if (dimension == null || dimension.isBlank())
                throw new IllegalArgumentException("dimension must not be blank");
            Objects.requireNonNull(origin);
            frontiers = List.copyOf(frontiers);
            Objects.requireNonNull(profile);
            Objects.requireNonNull(coverage);
            Objects.requireNonNull(heading);
            hint = hint == null ? Optional.empty() : hint;
            if (budgetTicks < 0 || tick < 0) throw new IllegalArgumentException("negative search budget or tick");
        }
    }

    /** One frontier with the score the ranking gave it; the driver reports the chosen and the nearest rejected. */
    public record Ranked(Frontier frontier, double score) {}

    public Optional<Frontier> choose(Request request) {
        return rank(request).stream().map(Ranked::frontier).findFirst();
    }

    /** Every frontier that survives the hard filters, best first. Deterministic for a given request. */
    public List<Ranked> rank(Request request) {
        Comparator<Ranked> order = Comparator.comparingDouble(Ranked::score)
                .reversed()
                .thenComparingInt(ranked -> -headingRank(request, ranked.frontier()))
                .thenComparingInt(ranked -> ranked.frontier().approach().x())
                .thenComparingInt(ranked -> ranked.frontier().approach().z())
                .thenComparingInt(ranked -> ranked.frontier().approach().y());
        return request.frontiers().stream()
                .filter(frontier -> request.profile().allows(request.dimension(), frontier.strategy()))
                .filter(frontier -> frontier.estimatedTravelTicks() <= request.budgetTicks())
                .filter(frontier -> !request.coverage()
                        .covered(
                                request.dimension(),
                                frontier.approach(),
                                request.profile().family(),
                                frontier.strategy(),
                                request.tick(),
                                6_000))
                .filter(frontier -> !blockedCooldown(request, frontier))
                .map(frontier -> new Ranked(frontier, score(request, frontier)))
                .sorted(order)
                .toList();
    }

    private static double score(Request request, Frontier f) {
        long recent = request.coverage()
                .recentVisits(
                        request.dimension(),
                        f.approach(),
                        request.profile().family(),
                        f.strategy(),
                        request.tick(),
                        6_000);
        long failed = request.coverage()
                .failedAttempts(
                        request.dimension(), f.approach(), request.profile().family(), f.strategy());
        double returnTicks = distance(request.origin(), f.approach()) * 2.0;
        double hint = request.hint()
                .map(value -> value.bonusFor(f, request.origin(), request.tick()))
                .orElse(0.0);
        return 8.0 * f.expectedNewViewpoints()
                - f.estimatedTravelTicks() / 20.0
                + (f.terrain().biomeTransition() ? 6.0 : 0.0)
                - 20.0 * f.terrain().waterFraction()
                - 30.0 * f.terrain().hazardFraction()
                + (f.terrain().onTrail() ? 4.0 : 0.0)
                - 4.0 * recent
                - 10.0 * failed
                - 2.0 * returnTicks / 20.0
                + hint;
    }

    private static boolean blockedCooldown(Request request, Frontier f) {
        return request.coverage().snapshot().stream()
                .anyMatch(v -> v.dimension().equals(request.dimension())
                        && v.viewpoint().equals(f.approach())
                        && v.family().equals(request.profile().family())
                        && v.strategy() == f.strategy()
                        && v.outcome() == SearchCoverage.Outcome.BLOCKED
                        && request.tick() >= v.tick()
                        && request.tick() - v.tick() < 6_000);
    }

    private static int headingRank(Request request, Frontier f) {
        int dx = f.approach().x() - request.origin().x();
        int dz = f.approach().z() - request.origin().z();
        Heading direction = Math.abs(dx) > Math.abs(dz)
                ? (dx > 0 ? Heading.EAST : Heading.WEST)
                : (dz < 0 ? Heading.NORTH : Heading.SOUTH);
        List<Heading> order = switch (request.heading()) {
            case NORTH -> List.of(Heading.NORTH, Heading.EAST, Heading.SOUTH, Heading.WEST);
            case EAST -> List.of(Heading.EAST, Heading.SOUTH, Heading.WEST, Heading.NORTH);
            case SOUTH -> List.of(Heading.SOUTH, Heading.WEST, Heading.NORTH, Heading.EAST);
            case WEST -> List.of(Heading.WEST, Heading.NORTH, Heading.EAST, Heading.SOUTH);
        };
        return -order.indexOf(direction);
    }

    private static double distance(Pos a, Pos b) {
        double dx = (double) a.x() - b.x(), dy = (double) a.y() - b.y(), dz = (double) a.z() - b.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
