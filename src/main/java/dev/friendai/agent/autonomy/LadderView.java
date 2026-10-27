package dev.friendai.agent.autonomy;

import java.util.ArrayList;
import java.util.List;

/**
 * Where the companion stands on the tool ladder.
 *
 * @param stage the first stage whose requirements are not all met, or the last one when everything is met
 * @param missingGear tools, armor and stations the stage still lacks
 * @param missingStock stock below the stage's target, as "coal 4/16"
 */
public record LadderView(String stage, List<String> missingGear, List<String> missingStock) {
    public LadderView {
        missingGear = List.copyOf(missingGear);
        missingStock = List.copyOf(missingStock);
    }

    public boolean complete() {
        return missingGear.isEmpty() && missingStock.isEmpty();
    }

    /** The development line of the director's prompt. */
    public String line() {
        if (complete()) {
            return "Development: stage " + stage + " complete";
        }
        List<String> missing = new ArrayList<>(missingGear);
        missing.addAll(missingStock);
        return "Development: stage " + stage + "; missing " + String.join(", ", missing);
    }
}
