package dev.friendai.features.farming;

import java.util.ArrayList;
import java.util.List;

/** An ordered list of steps plus what could not be covered; {@code describe()} is the LLM-facing summary. */
public record FarmPlan(List<FarmStep> steps, int unsown, List<String> notes) {
    public FarmPlan {
        steps = List.copyOf(steps);
        notes = List.copyOf(notes);
    }

    /** One English line, e.g. {@code harvest 4 and replant 4; sow 2; need 3 minecraft:wheat_seeds}. */
    public String describe() {
        int harvest = 0;
        int replant = 0;
        int sow = 0;
        int till = 0;
        int boneMeal = 0;
        List<String> parts = new ArrayList<>();
        List<String> needs = new ArrayList<>();
        for (FarmStep step : steps) {
            switch (step) {
                case FarmStep.Harvest ignored -> harvest++;
                case FarmStep.Replant ignored -> replant++;
                case FarmStep.Sow ignored -> sow++;
                case FarmStep.Till ignored -> till++;
                case FarmStep.Bonemeal ignored -> boneMeal++;
                case FarmStep.NeedSeeds need -> needs.add("need " + need.count() + " " + need.item());
            }
        }
        if (harvest > 0) {
            parts.add(replant > 0 ? "harvest " + harvest + " and replant " + replant : "harvest " + harvest);
        }
        if (sow > 0) {
            parts.add("sow " + sow);
        }
        if (till > 0) {
            parts.add("till " + till);
        }
        if (boneMeal > 0) {
            parts.add("bone meal " + boneMeal);
        }
        parts.addAll(needs);
        parts.addAll(notes);
        return parts.isEmpty() ? "nothing to do" : String.join("; ", parts);
    }
}
