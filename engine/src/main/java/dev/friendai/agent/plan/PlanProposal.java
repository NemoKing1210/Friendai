package dev.friendai.agent.plan;

import com.google.gson.JsonArray;
import dev.friendai.execution.queue.PlanDefinition;

/** A validated plan from the planner, retaining its tree and a normalized wire representation. */
public record PlanProposal(PlanDefinition plan, JsonArray stepsJson, String rationale, String expectedOutcome) {
    public PlanProposal {
        if (plan == null) throw new IllegalArgumentException("plan");
        stepsJson = stepsJson.deepCopy();
        rationale = rationale == null ? "" : rationale;
        expectedOutcome = expectedOutcome == null ? "" : expectedOutcome;
    }

    @Override
    public JsonArray stepsJson() {
        return stepsJson.deepCopy();
    }
}
