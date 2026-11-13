package dev.friendai.features.mining.tree;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Ranks revalidated tree observations by useful estimated lumber and the cost to fell and collect it. */
public final class TreeCandidateRanker {
    public enum Kind {
        STANDING_TREE,
        FLOATING,
        STUMP,
        LOG_STRUCTURE
    }

    /** Facts measured or estimated by the world adapter; ranking never proves that a log entered the inventory. */
    public record Candidate(
            TreeCell base,
            String species,
            Kind kind,
            int visibleLogs,
            int safelyCollectibleLogs,
            double approachCost,
            double climbCost,
            double collectionCost,
            boolean revalidated,
            boolean collectionKnown,
            boolean protectedRoute) {
        public Candidate {
            Objects.requireNonNull(base, "base");
            Objects.requireNonNull(kind, "kind");
            if (species == null || species.isBlank()) {
                throw new IllegalArgumentException("species is required");
            }
            if (visibleLogs < 0 || safelyCollectibleLogs < 0 || safelyCollectibleLogs > visibleLogs) {
                throw new IllegalArgumentException("log estimates must be between zero and visibleLogs");
            }
            requireCost(approachCost, "approachCost");
            requireCost(climbCost, "climbCost");
            requireCost(collectionCost, "collectionCost");
        }

        private static void requireCost(double cost, String name) {
            if (!Double.isFinite(cost) || cost < 0.0) {
                throw new IllegalArgumentException(name + " must be finite and non-negative");
            }
        }

        private boolean eligible() {
            if (!revalidated || protectedRoute || kind == Kind.LOG_STRUCTURE || visibleLogs == 0) {
                return false;
            }
            return collectionKnown ? safelyCollectibleLogs > 0 : true;
        }

        /** Unknown collection remains a potential estimate, not a zero-yield proof. */
        private int potentialLogs() {
            return collectionKnown ? safelyCollectibleLogs : visibleLogs;
        }

        private double totalCost() {
            return approachCost + climbCost + collectionCost;
        }

        private int coverageBand(int remaining) {
            if (collectionKnown) {
                return safelyCollectibleLogs >= remaining ? 0 : 1;
            }
            return 2;
        }
    }

    private TreeCandidateRanker() {}

    /** Returns eligible candidates in stable preference order for the current remaining log demand. */
    public static List<Candidate> rank(List<Candidate> candidates, int remaining) {
        Objects.requireNonNull(candidates, "candidates");
        if (remaining < 1) {
            throw new IllegalArgumentException("remaining must be positive");
        }
        Comparator<Candidate> order = Comparator.comparingInt(
                        (Candidate candidate) -> candidate.coverageBand(remaining))
                .thenComparing((Candidate candidate) ->
                        candidate.coverageBand(remaining) >= 1 ? -candidate.potentialLogs() : 0)
                .thenComparingDouble(Candidate::totalCost)
                .thenComparing(Candidate::base);
        return candidates.stream()
                .map(candidate -> Objects.requireNonNull(candidate, "candidate"))
                .filter(Candidate::eligible)
                .sorted(order)
                .toList();
    }
}
