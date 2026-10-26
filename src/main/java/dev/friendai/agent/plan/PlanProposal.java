package dev.friendai.agent.plan;

import com.google.gson.JsonArray;
import dev.friendai.queue.QueuedStep;
import java.util.List;

/**
 * A validated plan from the planner.
 *
 * @param steps the parsed steps, for display and tests
 * @param stepsJson the same steps in the {@code queue_add} wire shape (args as a JSON string), ready to apply
 */
public record PlanProposal(List<QueuedStep> steps, JsonArray stepsJson, String rationale, String expectedOutcome) {
    public PlanProposal {
        steps = List.copyOf(steps);
        stepsJson = stepsJson.deepCopy();
        rationale = rationale == null ? "" : rationale;
        expectedOutcome = expectedOutcome == null ? "" : expectedOutcome;
    }

    @Override
    public JsonArray stepsJson() {
        return stepsJson.deepCopy();
    }
}
