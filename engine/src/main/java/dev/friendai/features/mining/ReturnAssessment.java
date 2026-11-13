package dev.friendai.features.mining;

import dev.friendai.foundation.spatial.Node;
import java.util.List;
import java.util.Objects;

/** Pure check that a live, verified route still leads from an exploration stance back to its safe anchor. */
public final class ReturnAssessment {
    public enum Status {
        SAFE,
        NEEDS_SUPPORTS,
        UNKNOWN_OR_BLOCKED
    }

    public record Leg(Node from, Node to, int supports, boolean loaded, boolean verified) {
        public Leg {
            Objects.requireNonNull(from);
            Objects.requireNonNull(to);
        }
    }

    /** Return legs are ordered from the current stance back to the anchor. */
    public record RouteEvidence(
            String dimension,
            String loadedDimension,
            Node anchor,
            Node stance,
            List<Leg> returnLegs,
            boolean anchorLoaded,
            boolean fresh) {
        public RouteEvidence {
            returnLegs = List.copyOf(returnLegs);
        }
    }

    public record Result(Status status, Node anchor, int requiredSupports) {
        public Result {
            Objects.requireNonNull(status);
            if (requiredSupports < 0) throw new IllegalArgumentException("requiredSupports cannot be negative");
        }
    }

    private ReturnAssessment() {}

    public static Result assess(RouteEvidence evidence, int spareSupports) {
        if (evidence == null
                || evidence.dimension() == null
                || !evidence.dimension().equals(evidence.loadedDimension())
                || evidence.anchor() == null
                || evidence.stance() == null
                || !evidence.anchorLoaded()
                || !evidence.fresh()
                || spareSupports < 0) {
            return unknown(evidence);
        }

        Node expected = evidence.stance();
        int required = 0;
        for (Leg leg : evidence.returnLegs()) {
            if (leg == null
                    || !leg.loaded()
                    || !leg.verified()
                    || leg.supports() < 0
                    || !leg.from().equals(expected)) {
                return unknown(evidence);
            }
            required = (int) Math.min(Integer.MAX_VALUE, (long) required + leg.supports());
            expected = leg.to();
        }
        if (!expected.equals(evidence.anchor())) return unknown(evidence);
        return new Result(required <= spareSupports ? Status.SAFE : Status.NEEDS_SUPPORTS, evidence.anchor(), required);
    }

    private static Result unknown(RouteEvidence evidence) {
        return new Result(Status.UNKNOWN_OR_BLOCKED, evidence == null ? null : evidence.anchor(), 0);
    }
}
