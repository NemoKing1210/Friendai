package dev.friendai.combat;

import java.util.List;

/**
 * Ranks the nearby threats so the adapter fights the group, not the first entity it happened to see. Pure: each
 * candidate is a plain fact gathered on the server thread.
 *
 * <p>Ordering is deterministic and deliberately not "nearest first" alone: a mob that can already reach the
 * companion outranks a farther target it is currently chasing, and a mob that just hit outranks one that has not.
 */
public final class ThreatTable {
    /** A threat that hit the companion recently is the most urgent kind. */
    public static final double HIT_BONUS = 30.0;
    /** A threat already inside melee reach can land a hit now. */
    public static final double REACH_BONUS = 25.0;
    /** Per-block distance penalty. */
    public static final double DISTANCE_WEIGHT = 4.0;
    /** Small preference for the current target, so equal threats do not make selection oscillate. */
    public static final double CURRENT_BONUS = 8.0;
    /** A target beyond the owner's pursuit bound is not a candidate at all. */
    public static final double OUT_OF_BOUNDS_PENALTY = 1000.0;

    private ThreatTable() {}

    /**
     * @param distance straight-line distance to the companion
     * @param reachesNow the candidate can land a melee hit in this tick's position
     * @param attackedUs the candidate is the mob that last hurt the companion, recently
     * @param current the candidate is the target currently being fought
     * @param anchorDistance distance from the protected anchor (the owner, or the companion without an owner)
     */
    public record Candidate(
            double distance, boolean reachesNow, boolean attackedUs, boolean current, double anchorDistance) {}

    public static double score(Candidate candidate, int pursuitLimit) {
        double score = 0.0;
        if (candidate.attackedUs()) {
            score += HIT_BONUS;
        }
        if (candidate.reachesNow()) {
            score += REACH_BONUS;
        }
        score -= DISTANCE_WEIGHT * candidate.distance();
        if (candidate.current()) {
            score += CURRENT_BONUS;
        }
        if (candidate.anchorDistance() > pursuitLimit) {
            score -= OUT_OF_BOUNDS_PENALTY;
        }
        return score;
    }

    /** Index of the best candidate, or -1 when there is none. Ties keep the earlier candidate. */
    public static int best(List<Candidate> candidates, int pursuitLimit) {
        int best = -1;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < candidates.size(); i++) {
            double score = score(candidates.get(i), pursuitLimit);
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }
}
