package dev.friendai.features.acquisition;

/**
 * Whether a finished acquisition step actually changed something the next plan can use. A child's reported status
 * says it ran the action, not that the action had an effect: a mined block can drop into lava or the void, a craft
 * can be reported done while the bag never gained its product. The acquisition credits the step only on one of the
 * measured effects — the carried inventory changed, the plan gained a remembered or visible source, or a visit
 * verified the site — and treats a nominal success with none of them as an attempt that yielded nothing.
 */
public final class StepVerification {
    private StepVerification() {}

    /**
     * @param step the step that just reported success
     * @param verifiedObservation whether a resource visit settled the site as present
     * @param carriedChanged whether the carried inventory differs from the one the step started with
     * @param candidatesBefore remembered or visible sources the plan had when the step started
     * @param candidatesAfter the same count on the replan
     */
    public static boolean progressed(
            ObtainStep step,
            boolean verifiedObservation,
            boolean carriedChanged,
            int candidatesBefore,
            int candidatesAfter) {
        return switch (step) {
            case ObtainStep.VisitResource ignored -> verifiedObservation;
            // A trader inspection succeeds by reading the offers, which the plan reads in its own place; it has no
            // carried or candidate effect the acquisition can see, so it is not judged here.
            case ObtainStep.InspectTrader ignored -> true;
            default -> carriedChanged || candidatesAfter > candidatesBefore;
        };
    }
}
