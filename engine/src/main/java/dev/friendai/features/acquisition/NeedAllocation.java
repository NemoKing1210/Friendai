package dev.friendai.features.acquisition;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Ranks the candidate items of one material need and splits the need across them.
 *
 * <p>A need is the demand one recipe label leaves behind, satisfied by any of its candidates (the members of a tag,
 * for example). The batch used to take the first candidate whose plan was executable, so a candidate that could
 * supply part of the demand was skipped and a speculative route could win over stock already at hand. This policy
 * compares the plans instead: a complete plan first, then a plan that supplies part, then one that supplies nothing,
 * and a work-limited plan last because it is not evidence either way. Within a tier, local supply comes before a
 * remembered site, which comes before exploration; more supply, then fewer steps, then the caller's preference order
 * break ties. The split takes the covered count from each candidate in that order, so a need can be met from several
 * sources without asking any single one for more than it can supply.
 */
public final class NeedAllocation {
    private NeedAllocation() {}

    /** One candidate item for a need, the plan the planner produced for the whole deficit, and its preference rank. */
    public record Candidate(String item, ObtainPlan plan, int preference) {
        public Candidate {
            Objects.requireNonNull(item, "item");
            Objects.requireNonNull(plan, "plan");
        }
    }

    /** How much of a need one candidate should gather. */
    public record Leg(String item, int count) {
        public Leg {
            Objects.requireNonNull(item, "item");
            if (count <= 0) {
                throw new IllegalArgumentException("a leg gathers a positive count: " + count);
            }
        }
    }

    private static final Comparator<Candidate> RANKING = Comparator.comparingInt(
                    (Candidate candidate) -> tier(candidate.plan()))
            .thenComparingInt(candidate -> evidence(candidate.plan()))
            .thenComparing(candidate -> -candidate.plan().supplied())
            .thenComparingInt(candidate -> candidate.plan().estimatedSteps())
            .thenComparingInt(Candidate::preference)
            .thenComparing(Candidate::item);

    /** The candidates in the order this policy would spend them. */
    public static List<Candidate> rank(List<Candidate> candidates) {
        List<Candidate> ranked = new ArrayList<>(candidates);
        ranked.sort(RANKING);
        return List.copyOf(ranked);
    }

    /**
     * The legs that cover {@code needed}, in ranked order; a candidate contributes at most what it can supply and a
     * work-limited candidate contributes nothing. The result may cover less than {@code needed}; it is empty when no
     * candidate can supply anything here.
     */
    public static List<Leg> allocate(int needed, List<Candidate> candidates) {
        if (needed <= 0) {
            throw new IllegalArgumentException("a need is positive: " + needed);
        }
        List<Leg> legs = new ArrayList<>();
        int remaining = needed;
        for (Candidate candidate : rank(candidates)) {
            if (remaining <= 0) {
                break;
            }
            if (!candidate.plan().hasSupply()) {
                continue;
            }
            int count = Math.min(remaining, candidate.plan().supplied());
            legs.add(new Leg(candidate.item(), count));
            remaining -= count;
        }
        return List.copyOf(legs);
    }

    private static int tier(ObtainPlan plan) {
        if (plan.limited()) {
            return 3;
        }
        if (plan.actionable()) {
            return 0;
        }
        return plan.hasSupply() ? 1 : 2;
    }

    private static int evidence(ObtainPlan plan) {
        int level = 0;
        for (ObtainStep step : plan.steps()) {
            if (step instanceof ObtainStep.Explore) {
                return 2;
            }
            if (step instanceof ObtainStep.VisitResource) {
                level = 1;
            }
        }
        return level;
    }
}
