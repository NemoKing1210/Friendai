package dev.friendai.features.fire;

import java.util.List;
import java.util.Optional;

/**
 * What the companion will do about one target, in order, plus the reasoning worth telling the model.
 *
 * @param steps the actions; a {@link ExtinguishStep.GiveUp} is always last when the plan cannot finish
 * @param notes free-form remarks, e.g. that rain is already doing the work
 */
public record ExtinguishPlan(FireTarget target, List<ExtinguishStep> steps, List<String> notes) {

    public ExtinguishPlan {
        steps = List.copyOf(steps);
        notes = List.copyOf(notes);
    }

    /** The reason the plan stops short, or empty when it intends to finish. */
    public Optional<String> giveUp() {
        for (ExtinguishStep step : steps) {
            if (step instanceof ExtinguishStep.GiveUp giveUp) {
                return Optional.of(giveUp.reason());
            }
        }
        return Optional.empty();
    }

    /** How many fire cells the plan still has to act on. */
    public int workCount() {
        return (int) steps.stream()
                .filter(step -> step instanceof ExtinguishStep.PlaceWater
                        || step instanceof ExtinguishStep.DouseCampfire
                        || step instanceof ExtinguishStep.EnterWater
                        || step instanceof ExtinguishStep.StandInPowderSnow
                        || step instanceof ExtinguishStep.BreakFire)
                .count();
    }
}
