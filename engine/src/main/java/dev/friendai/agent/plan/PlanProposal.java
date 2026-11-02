package dev.friendai.agent.plan;

import com.google.gson.JsonArray;
import dev.friendai.execution.queue.PlanDefinition;

/** A validated plan from the planner, retaining its tree and a normalized wire representation. */
public record PlanProposal(
        PlanDefinition plan,
        JsonArray stepsJson,
        String rationale,
        String expectedOutcome,
        long targetPlanId,
        String targetFingerprint) {
    public PlanProposal {
        if (plan == null) throw new IllegalArgumentException("plan");
        stepsJson = stepsJson.deepCopy();
        rationale = rationale == null ? "" : rationale;
        expectedOutcome = expectedOutcome == null ? "" : expectedOutcome;
        targetFingerprint = targetFingerprint == null ? "" : targetFingerprint;
    }

    /** A proposal with no arbitrary-plan identity, preserving older callers. */
    public PlanProposal(PlanDefinition plan, JsonArray stepsJson, String rationale, String expectedOutcome) {
        this(plan, stepsJson, rationale, expectedOutcome, 0, "");
    }

    @Override
    public JsonArray stepsJson() {
        return stepsJson.deepCopy();
    }
}
