package dev.friendai.features.knowledge;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Bounded ordering of recorded containers for a withdrawal: a live record (opened within
 * {@link ContainerFreshness#LIVE_TICKS}) beats a remembered one, then the nearer container, then the one believed to
 * hold more, then the position. Records in another dimension, older than
 * {@link ContainerFreshness#REMEMBER_TICKS}, outside the radius, or holding none of the requested items are never
 * candidates.
 *
 * <p>Nothing here reads the world. A remembered record is a hint the caller revalidates by opening the container, and
 * the executor continues to the next candidate when the hint is wrong.
 */
public final class ContainerWithdrawals {
    /** How many containers one withdrawal may try, so an order cannot become a long walk. */
    public static final int MAX_CANDIDATES = 8;

    /** One recorded container offered for a withdrawal, with the count it is believed to hold. */
    public record Candidate(ResourceObservation.Location location, int count, boolean live) {
        public Candidate {
            Objects.requireNonNull(location, "location");
            if (count <= 0) throw new IllegalArgumentException("count must be positive");
        }
    }

    private ContainerWithdrawals() {}

    public static List<Candidate> rank(
            Collection<ResourceKnowledge.ContainerObservation> observations,
            Predicate<String> item,
            String dimension,
            int x,
            int y,
            int z,
            long nowTick,
            int radius) {
        return rank(observations, item, dimension, x, y, z, nowTick, radius, MAX_CANDIDATES);
    }

    public static List<Candidate> rank(
            Collection<ResourceKnowledge.ContainerObservation> observations,
            Predicate<String> item,
            String dimension,
            int x,
            int y,
            int z,
            long nowTick,
            int radius,
            int max) {
        if (observations == null || observations.isEmpty() || item == null || dimension == null) {
            return List.of();
        }
        long now = Math.max(0, nowTick);
        double radiusSquared = (double) Math.max(0, radius) * Math.max(0, radius);
        List<Candidate> candidates = new ArrayList<>();
        for (ResourceKnowledge.ContainerObservation observation : observations) {
            if (observation == null) continue;
            ResourceObservation.Location location = observation.location();
            if (!dimension.equals(location.dimension())
                    || !ContainerFreshness.remembered(observation.observedTick(), now)) continue;
            var pos = location.pos();
            double dx = pos.x() - x;
            double dy = pos.y() - y;
            double dz = pos.z() - z;
            if (dx * dx + dy * dy + dz * dz > radiusSquared) continue;
            int count = 0;
            for (var entry : observation.items().entrySet()) {
                if (item.test(entry.getKey())) count += entry.getValue();
            }
            if (count <= 0) continue;
            candidates.add(new Candidate(location, count, ContainerFreshness.live(observation.observedTick(), now)));
        }
        candidates.sort(Comparator.comparing((Candidate candidate) -> !candidate.live())
                .thenComparingDouble(candidate -> distanceSq(candidate.location(), x, y, z))
                .thenComparing(Comparator.comparingInt(Candidate::count).reversed())
                .thenComparing(candidate -> candidate.location().pos().x())
                .thenComparing(candidate -> candidate.location().pos().y())
                .thenComparing(candidate -> candidate.location().pos().z()));
        int limit = Math.max(1, max);
        return candidates.size() <= limit ? List.copyOf(candidates) : List.copyOf(candidates.subList(0, limit));
    }

    private static double distanceSq(ResourceObservation.Location location, int x, int y, int z) {
        var pos = location.pos();
        double dx = pos.x() - x;
        double dy = pos.y() - y;
        double dz = pos.z() - z;
        return dx * dx + dy * dy + dz * dz;
    }
}
