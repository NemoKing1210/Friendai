package dev.friendai.agent.plan;

import com.google.gson.JsonArray;
import dev.friendai.execution.queue.PlanDefinition;
import dev.friendai.execution.queue.PlanNode;
import dev.friendai.execution.queue.QueuedStep;
import java.util.ArrayList;
import java.util.List;

/** A validated plan from the planner, retaining its tree and a normalized wire representation. */
public record PlanProposal(PlanDefinition plan, JsonArray stepsJson, String rationale, String expectedOutcome) {
    public PlanProposal {
        if (plan == null) throw new IllegalArgumentException("plan");
        stepsJson = stepsJson.deepCopy();
        rationale = rationale == null ? "" : rationale;
        expectedOutcome = expectedOutcome == null ? "" : expectedOutcome;
    }

    /** Flat compatibility view for older planner integrations and tests. */
    public List<QueuedStep> steps() {
        List<QueuedStep> result = new ArrayList<>();
        collect(plan.nodes(), result);
        return List.copyOf(result);
    }

    @Override
    public JsonArray stepsJson() {
        return stepsJson.deepCopy();
    }

    private static void collect(List<PlanNode> nodes, List<QueuedStep> result) {
        for (PlanNode node : nodes) {
            if (node instanceof PlanNode.Action action) result.add(action.step());
            else if (node instanceof PlanNode.Conditional conditional) {
                collect(conditional.thenNodes(), result);
                collect(conditional.elseNodes(), result);
            } else collect(((PlanNode.Repeat) node).body(), result);
        }
    }
}
