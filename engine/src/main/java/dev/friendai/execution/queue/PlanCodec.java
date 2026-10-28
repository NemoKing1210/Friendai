package dev.friendai.execution.queue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;

/** Canonical JSON encoding for validated plan definitions. */
public final class PlanCodec {
    private PlanCodec() {}

    public static JsonArray toJson(PlanDefinition plan) {
        JsonArray result = new JsonArray();
        plan.nodes().forEach(node -> result.add(node(node)));
        return result;
    }

    /** Decodes the canonical plan JSON used in versioned queue saves. */
    public static PlanDefinition fromJson(JsonArray json) {
        if (json == null || json.isEmpty()) throw new IllegalArgumentException("empty plan");
        return new PlanDefinition(readNodes(json, 1));
    }

    public static JsonObject actionJson(QueuedStep step) {
        return node(new PlanNode.Action(step));
    }

    public static QueuedStep readAction(JsonObject object) {
        if (!onlyKeys(
                object,
                "tool",
                "args",
                "priority",
                "when",
                "after",
                "on_unmet",
                "on_failure",
                "max_attempts",
                "principal",
                "creative_sensitive")) throw new IllegalArgumentException("unknown saved action field");
        if (!object.has("tool")
                || !object.get("tool").isJsonPrimitive()
                || !object.getAsJsonPrimitive("tool").isString()) throw new IllegalArgumentException("invalid action");
        String tool = object.get("tool").getAsString();
        JsonObject args = new JsonObject();
        if (object.has("args")) {
            JsonElement raw = object.get("args");
            if (raw.isJsonObject()) args = raw.getAsJsonObject().deepCopy();
            else if (raw.isJsonPrimitive() && raw.getAsJsonPrimitive().isString()) {
                JsonElement parsed = JsonParser.parseString(raw.getAsString());
                if (!parsed.isJsonObject()) throw new IllegalArgumentException("invalid saved action args");
                args = parsed.getAsJsonObject().deepCopy();
            } else throw new IllegalArgumentException("invalid saved action args");
        }
        Integer priority = object.has("priority") ? strictInt(object.get("priority")) : null;
        if (priority != null && (priority < -10 || priority > 10))
            throw new IllegalArgumentException("invalid saved priority");
        List<Guard> guards = new java.util.ArrayList<>();
        if (object.has("when"))
            for (JsonElement guard : object.getAsJsonArray("when")) {
                if (!guard.isJsonObject()) throw new IllegalArgumentException("invalid saved guard");
                guards.add(Guard.parse(guard.getAsJsonObject()).orElseThrow());
            }
        List<Integer> after = new java.util.ArrayList<>();
        if (object.has("after"))
            for (JsonElement dependency : object.getAsJsonArray("after")) after.add(strictInt(dependency));
        GuardAction onUnmet = object.has("on_unmet")
                ? GuardAction.valueOf(object.get("on_unmet").getAsString().toUpperCase())
                : GuardAction.WAIT;
        FailurePolicy failure = object.has("on_failure")
                ? switch (object.get("on_failure").getAsString()) {
                    case "ask" -> FailurePolicy.ASK;
                    case "skip" -> FailurePolicy.SKIP;
                    case "stop_plan" -> FailurePolicy.STOP_PLAN;
                    default -> throw new IllegalArgumentException("invalid saved action failure policy");
                }
                : null;
        int attempts = object.has("max_attempts") ? strictInt(object.get("max_attempts")) : 1;
        if (attempts < 1 || attempts > 3) throw new IllegalArgumentException("invalid saved attempt count");
        java.util.UUID principal = object.has("principal")
                ? java.util.UUID.fromString(object.get("principal").getAsString())
                : null;
        boolean creativeSensitive = !object.has("creative_sensitive")
                || object.get("creative_sensitive").getAsBoolean();
        return new QueuedStep(
                tool, args, priority, guards, onUnmet, failure, attempts, after, principal, creativeSensitive);
    }

    private static java.util.List<PlanNode> readNodes(JsonArray nodes, int depth) {
        if (depth > 8) throw new IllegalArgumentException("plan nesting too deep");
        java.util.List<PlanNode> result = new java.util.ArrayList<>();
        for (JsonElement element : nodes) {
            if (!element.isJsonObject()) throw new IllegalArgumentException("invalid plan node");
            JsonObject object = element.getAsJsonObject();
            if (object.has("tool")) {
                result.add(new PlanNode.Action(readAction(object)));
            } else if (object.has("when")
                    && object.has("then")
                    && object.get("when").isJsonObject()) {
                if (!onlyKeys(object, "when", "then", "else"))
                    throw new IllegalArgumentException("unknown saved conditional field");
                Guard condition = Guard.parse(object.getAsJsonObject("when")).orElseThrow();
                result.add(new PlanNode.Conditional(
                        condition,
                        readNodes(object.getAsJsonArray("then"), depth + 1),
                        object.has("else")
                                ? readNodes(object.getAsJsonArray("else"), depth + 1)
                                : java.util.List.of()));
            } else if (object.size() == 1
                    && object.has("repeat")
                    && object.get("repeat").isJsonObject()) {
                JsonObject repeat = object.getAsJsonObject("repeat");
                if (!onlyKeys(repeat, "steps", "until", "max_iterations"))
                    throw new IllegalArgumentException("unknown saved repeat field");
                result.add(new PlanNode.Repeat(
                        readNodes(repeat.getAsJsonArray("steps"), depth + 1),
                        Guard.parse(repeat.getAsJsonObject("until")).orElseThrow(),
                        strictInt(repeat.get("max_iterations"))));
            } else throw new IllegalArgumentException("invalid plan node shape");
        }
        return result;
    }

    private static boolean onlyKeys(JsonObject object, String... keys) {
        for (String key : object.keySet()) {
            boolean known = false;
            for (String allowed : keys)
                if (allowed.equals(key)) {
                    known = true;
                    break;
                }
            if (!known) return false;
        }
        return true;
    }

    private static int strictInt(JsonElement element) {
        if (element == null
                || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("invalid integer");
        String raw = element.getAsString();
        if (raw.contains(".") || raw.contains("e") || raw.contains("E"))
            throw new IllegalArgumentException("invalid integer");
        return Integer.parseInt(raw);
    }

    private static JsonObject node(PlanNode node) {
        if (node instanceof PlanNode.Action action) {
            QueuedStep step = action.step();
            JsonObject result = new JsonObject();
            result.addProperty("tool", step.tool());
            result.addProperty("args", step.args().toString());
            if (step.principal() != null)
                result.addProperty("principal", step.principal().toString());
            if (!step.creativeSensitive()) result.addProperty("creative_sensitive", false);
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
            if (step.onFailure() != null)
                result.addProperty(
                        "on_failure",
                        switch (step.onFailure()) {
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
