package dev.friendai.queue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** Canonical JSON encoding for validated plan definitions. */
public final class PlanCodec {
    private PlanCodec() { }

    public static JsonArray toJson(PlanDefinition plan) {
        JsonArray result = new JsonArray();
        plan.nodes().forEach(node -> result.add(node(node)));
        return result;
    }

    private static JsonObject node(PlanNode node) {
        if (node instanceof PlanNode.Action action) {
            QueuedStep step = action.step();
            JsonObject result = new JsonObject();
            result.addProperty("tool", step.tool());
            result.addProperty("args", step.args().toString());
            if (step.priority() != null) result.addProperty("priority", step.priority());
            if (!step.guards().isEmpty()) {
                JsonArray guards = new JsonArray();
                step.guards().forEach(guard -> guards.add(guard.toJson()));
                result.add("when", guards);
            }
            if (!step.afterSteps().isEmpty()) {
                JsonArray after = new JsonArray();
                step.afterSteps().forEach(after::add);
                result.add("after", after);
            }
            result.addProperty("on_unmet", step.onUnmet() == GuardAction.SKIP ? "skip" : "wait");
            if (step.onFailure() != null) result.addProperty("on_failure", switch (step.onFailure()) {
                case ASK -> "ask";
                case SKIP -> "skip";
                case STOP_PLAN -> "stop_plan";
            });
            result.addProperty("max_attempts", step.maxAttempts());
            return result;
        }
        if (node instanceof PlanNode.Conditional conditional) {
            JsonObject result = new JsonObject();
            result.add("when", conditional.condition().toJson());
            result.add("then", nodes(conditional.thenNodes()));
            result.add("else", nodes(conditional.elseNodes()));
            return result;
        }
        PlanNode.Repeat repeat = (PlanNode.Repeat) node;
        JsonObject wrapper = new JsonObject();
        JsonObject body = new JsonObject();
        body.add("steps", nodes(repeat.body()));
        body.add("until", repeat.until().toJson());
        body.addProperty("max_iterations", repeat.maxIterations());
        wrapper.add("repeat", body);
        return wrapper;
    }

    private static JsonArray nodes(java.util.List<PlanNode> nodes) {
        JsonArray result = new JsonArray();
        nodes.forEach(node -> result.add(node(node)));
        return result;
    }
}
