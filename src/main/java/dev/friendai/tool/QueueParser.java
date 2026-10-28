package dev.friendai.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.friendai.queue.FailurePolicy;
import dev.friendai.queue.Guard;
import dev.friendai.queue.GuardAction;
import dev.friendai.queue.PlanCodec;
import dev.friendai.queue.PlanDefinition;
import dev.friendai.queue.PlanNode;
import dev.friendai.queue.QueuedStep;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Pure validation of flat queues and conditional plan trees. */
public final class QueueParser {
    public record Result(List<QueuedStep> steps, List<String> errors) {
        public Result {
            steps = List.copyOf(steps);
            errors = List.copyOf(errors);
        }

        public boolean ok() {
            return errors.isEmpty();
        }
    }

    public record PlanResult(PlanDefinition plan, List<String> errors) {
        public PlanResult {
            errors = List.copyOf(errors);
        }

        public boolean ok() {
            return plan != null && errors.isEmpty();
        }
    }

    public static final int MAX_GUARDS = 8, MAX_AFTER = 8, MAX_REPEAT = 64, MAX_NODE_DEPTH = 8;

    private QueueParser() {}

    /** Compatibility entry point for callers that still require a flat action list. */
    public static Result parse(
            JsonArray input,
            Map<String, JsonObject> schemas,
            Set<String> allTools,
            int maxSteps,
            int maxArgs,
            int maxTotal) {
        PlanResult parsed = parsePlan(input, schemas, allTools, maxSteps, maxArgs, maxTotal);
        if (!parsed.ok()) return new Result(List.of(), parsed.errors());
        List<QueuedStep> flat = new ArrayList<>();
        for (PlanNode node : parsed.plan().nodes()) {
            if (!(node instanceof PlanNode.Action action)) {
                return new Result(List.of(), List.of("conditional plans are not supported by this queue operation"));
            }
            flat.add(action.step());
        }
        return new Result(flat, List.of());
    }

    public static PlanResult parsePlan(
            JsonArray input,
            Map<String, JsonObject> schemas,
            Set<String> allTools,
            int maxSteps,
            int maxArgs,
            int maxTotal) {
        if (input == null || input.isEmpty()) return new PlanResult(null, List.of("a plan needs at least 1 step"));
        List<String> errors = new ArrayList<>();
        Counter counter = new Counter();
        List<PlanNode> nodes = parseNodes(input, "", 1, schemas, allTools, maxArgs, counter, errors);
        PlanDefinition plan = null;
        if (errors.isEmpty()) {
            try {
                plan = new PlanDefinition(nodes);
            } catch (RuntimeException ignored) {
                errors.add("plan: invalid structure");
            }
        }
        if (plan != null && plan.worstCaseActions() > Math.max(0, maxSteps)) {
            errors.add("plan: worst-case action count exceeds " + maxSteps);
        }
        if (plan != null && PlanCodec.toJson(plan).toString().length() > Math.max(0, maxTotal)) {
            errors.add("plan: normalized JSON exceeds " + maxTotal + " characters");
        }
        if (counter.argumentChars > Math.max(0, maxTotal)) {
            errors.add("plan: aggregate arguments exceed " + maxTotal + " characters");
        }
        return errors.isEmpty() ? new PlanResult(plan, List.of()) : new PlanResult(null, errors);
    }

    private static List<PlanNode> parseNodes(
            JsonArray input,
            String parentPath,
            int depth,
            Map<String, JsonObject> schemas,
            Set<String> allTools,
            int maxArgs,
            Counter counter,
            List<String> errors) {
        List<PlanNode> result = new ArrayList<>();
        if (depth > MAX_NODE_DEPTH) {
            errors.add(path(parentPath, "plan nesting exceeds " + MAX_NODE_DEPTH + " levels"));
            return result;
        }
        for (int i = 0; i < input.size(); i++) {
            String nodePath = stepPath(parentPath, i + 1);
            JsonElement element = input.get(i);
            if (!element.isJsonObject()) {
                errors.add(path(nodePath, "must be an object"));
                continue;
            }
            JsonObject object = element.getAsJsonObject();
            boolean action = object.has("tool");
            boolean conditional = object.has("then")
                    || object.has("else")
                    || object.has("when") && object.get("when").isJsonObject();
            boolean repeat = object.has("repeat");
            if ((action ? 1 : 0) + (conditional ? 1 : 0) + (repeat ? 1 : 0) != 1) {
                errors.add(path(nodePath, "must be exactly one action, conditional, or repeat node"));
                continue;
            }
            if (action) {
                PlanNode.Action parsed = parseAction(object, nodePath, i, schemas, allTools, maxArgs, counter, errors);
                if (parsed != null) result.add(parsed);
            } else if (conditional) {
                PlanNode.Conditional parsed =
                        parseConditional(object, nodePath, depth, schemas, allTools, maxArgs, counter, errors);
                if (parsed != null) result.add(parsed);
            } else {
                PlanNode.Repeat parsed =
                        parseRepeat(object, nodePath, depth, schemas, allTools, maxArgs, counter, errors);
                if (parsed != null) result.add(parsed);
            }
        }
        return result;
    }

    private static PlanNode.Action parseAction(
            JsonObject obj,
            String path,
            int localIndex,
            Map<String, JsonObject> schemas,
            Set<String> allTools,
            int maxArgs,
            Counter counter,
            List<String> errors) {
        if (!onlyKeys(obj, "tool", "args", "priority", "when", "after", "on_unmet", "on_failure", "max_attempts")) {
            errors.add(path(path, "action has unknown fields"));
            return null;
        }
        String tool = strictString(obj, "tool");
        if (tool == null) {
            errors.add(path(path, "tool must be a string"));
            return null;
        }
        if (!allTools.contains(tool)) {
            errors.add(path(path, "unknown tool '" + tool + "'"));
            return null;
        }
        JsonObject schema = schemas.get(tool);
        if (schema == null) {
            errors.add(path(path, "'" + tool + "' cannot be used in a plan"));
            return null;
        }

        JsonObject args = new JsonObject();
        if (obj.has("args")) {
            JsonElement argsElement = obj.get("args");
            if (!argsElement.isJsonPrimitive()
                    || !argsElement.getAsJsonPrimitive().isString()) {
                errors.add(path(path, "args must be a JSON object string"));
                return null;
            }
            String raw = argsElement.getAsString();
            try {
                if (!raw.isBlank()) {
                    JsonElement parsed = JsonParser.parseString(raw);
                    if (!parsed.isJsonObject()) throw new IllegalArgumentException();
                    args = parsed.getAsJsonObject().deepCopy();
                }
            } catch (RuntimeException ex) {
                errors.add(path(path, "args is not a JSON object"));
                return null;
            }
        }
        int argSize = args.toString().length();
        counter.argumentChars += argSize;
        if (argSize > maxArgs) errors.add(path(path, "arguments exceed " + maxArgs + " characters"));
        List<String> argIssues = ArgValidator.validate(schema, args);
        for (String issue : argIssues) errors.add(path(path, issue));

        List<Guard> guards = new ArrayList<>();
        if (obj.has("when")) {
            JsonElement when = obj.get("when");
            if (!when.isJsonArray()) errors.add(path(path, "action when must be a legacy guard array"));
            else {
                JsonArray rows = when.getAsJsonArray();
                if (rows.size() > MAX_GUARDS) errors.add(path(path, "at most " + MAX_GUARDS + " guards"));
                for (int i = 0; i < Math.min(rows.size(), MAX_GUARDS); i++) {
                    Guard guard = parseGuard(rows.get(i), path + " → when #" + (i + 1), 1, errors);
                    if (guard != null) guards.add(guard);
                }
            }
        }
        List<Integer> after = parseAfter(obj, path, localIndex, errors);
        GuardAction onUnmet = GuardAction.WAIT;
        if (obj.has("on_unmet")) {
            String value = strictString(obj, "on_unmet");
            if ("skip".equals(value)) onUnmet = GuardAction.SKIP;
            else if (!"wait".equals(value)) errors.add(path(path, "on_unmet must be 'wait' or 'skip'"));
        }
        FailurePolicy failure = null;
        if (obj.has("on_failure")) {
            String value = strictString(obj, "on_failure");
            failure = value == null ? null : policy(value).orElse(null);
            if (failure == null) errors.add(path(path, "on_failure must be ask, skip, or stop_plan"));
        }
        Integer priority = strictInteger(obj, "priority", null, path, errors);
        if (priority != null && (priority < -10 || priority > 10))
            errors.add(path(path, "priority must be between -10 and 10"));
        Integer attemptsValue = strictInteger(obj, "max_attempts", 1, path, errors);
        int attempts = attemptsValue == null ? 1 : attemptsValue;
        if (attempts < 1 || attempts > 3) errors.add(path(path, "max_attempts must be between 1 and 3"));
        if (errorsBelow(errors, path)) return null;
        return new PlanNode.Action(new QueuedStep(tool, args, priority, guards, onUnmet, failure, attempts, after));
    }

    private static PlanNode.Conditional parseConditional(
            JsonObject obj,
            String path,
            int depth,
            Map<String, JsonObject> schemas,
            Set<String> allTools,
            int maxArgs,
            Counter counter,
            List<String> errors) {
        if (!onlyKeys(obj, "when", "then", "else") || !obj.has("when") || !obj.has("then")) {
            errors.add(path(path + " → if", "requires only when, then, and optional else fields"));
            return null;
        }
        Guard condition = parseGuard(obj.get("when"), path + " → if → condition", 1, errors);
        JsonArray thenArray = array(obj.get("then"), path + " → if → then", true, errors);
        JsonArray elseArray =
                obj.has("else") ? array(obj.get("else"), path + " → if → else", false, errors) : new JsonArray();
        List<PlanNode> thenNodes = thenArray == null
                ? List.of()
                : parseNodes(thenArray, path + " → then", depth + 1, schemas, allTools, maxArgs, counter, errors);
        List<PlanNode> elseNodes = elseArray == null
                ? List.of()
                : parseNodes(elseArray, path + " → else", depth + 1, schemas, allTools, maxArgs, counter, errors);
        if (condition == null || thenArray == null || elseArray == null) return null;
        return new PlanNode.Conditional(condition, thenNodes, elseNodes);
    }

    private static PlanNode.Repeat parseRepeat(
            JsonObject obj,
            String path,
            int depth,
            Map<String, JsonObject> schemas,
            Set<String> allTools,
            int maxArgs,
            Counter counter,
            List<String> errors) {
        String repeatPath = path + " → repeat";
        if (obj.size() != 1 || !obj.get("repeat").isJsonObject()) {
            errors.add(path(repeatPath, "must be the only field and contain an object"));
            return null;
        }
        JsonObject repeat = obj.getAsJsonObject("repeat");
        if (!onlyKeys(repeat, "steps", "until", "max_iterations")
                || !repeat.has("steps")
                || !repeat.has("until")
                || !repeat.has("max_iterations")) {
            errors.add(path(repeatPath, "requires only steps, until, and max_iterations fields"));
            return null;
        }
        JsonArray bodyArray = array(repeat.get("steps"), repeatPath + " → steps", true, errors);
        Guard until = parseGuard(repeat.get("until"), repeatPath + " → until", 1, errors);
        Integer iterations = strictInteger(repeat, "max_iterations", null, repeatPath, errors);
        if (iterations != null && (iterations < 1 || iterations > MAX_REPEAT))
            errors.add(path(repeatPath, "max_iterations must be between 1 and " + MAX_REPEAT));
        List<PlanNode> body = bodyArray == null
                ? List.of()
                : parseNodes(bodyArray, repeatPath, depth + 1, schemas, allTools, maxArgs, counter, errors);
        if (bodyArray == null
                || until == null
                || iterations == null
                || iterations < 1
                || iterations > MAX_REPEAT
                || body.isEmpty()) return null;
        try {
            return new PlanNode.Repeat(body, until, iterations);
        } catch (RuntimeException ex) {
            errors.add(path(repeatPath, "invalid repeat"));
            return null;
        }
    }

    private static Guard parseGuard(JsonElement element, String path, int depth, List<String> errors) {
        if (depth > Guard.MAX_DEPTH) {
            errors.add(path(path, "condition nesting exceeds " + Guard.MAX_DEPTH + " levels"));
            return null;
        }
        if (element == null || !element.isJsonObject()) {
            errors.add(path(path, "condition must be an object"));
            return null;
        }
        JsonObject json = element.getAsJsonObject();
        String type = strictString(json, "type");
        if (type == null) {
            errors.add(path(path, "condition type must be a string"));
            return null;
        }
        if (type.equals("all_of") || type.equals("any_of")) {
            if (!onlyKeys(json, "type", "conditions")
                    || !json.has("conditions")
                    || !json.get("conditions").isJsonArray()) {
                errors.add(path(path, type + " requires a conditions array"));
                return null;
            }
            JsonArray children = json.getAsJsonArray("conditions");
            if (children.isEmpty() || children.size() > Guard.MAX_CHILDREN) {
                errors.add(path(path, type + " must contain 1 to " + Guard.MAX_CHILDREN + " conditions"));
                return null;
            }
            boolean valid = true;
            for (int i = 0; i < children.size(); i++) {
                if (parseGuard(children.get(i), path + " → " + type + " #" + (i + 1), depth + 1, errors) == null)
                    valid = false;
            }
            if (!valid) return null;
        } else if (type.equals("not")) {
            if (!onlyKeys(json, "type", "condition") || !json.has("condition")) {
                errors.add(path(path, "not requires a condition object"));
                return null;
            }
            if (parseGuard(json.get("condition"), path + " → not", depth + 1, errors) == null) return null;
        }
        if (!validGuardFields(json, type)) {
            errors.add(path(path, "invalid fields for condition '" + type + "'"));
            return null;
        }
        Optional<Guard> parsed = Guard.parse(json);
        if (parsed.isEmpty()) {
            errors.add(path(path, "invalid or unknown condition '" + type + "'"));
            return null;
        }
        return parsed.get();
    }

    private static boolean validGuardFields(JsonObject json, String type) {
        String[] keys = switch (type) {
            case "has_item" -> new String[] {"type", "item", "count"};
            case "inventory_space" -> new String[] {"type", "slots"};
            case "health_at_least" -> new String[] {"type", "percent"};
            case "owner_within" -> new String[] {"type", "blocks"};
            case "block_within" -> new String[] {"type", "block", "radius", "count"};
            case "dimension" -> new String[] {"type", "dimension"};
            case "all_of", "any_of" -> new String[] {"type", "conditions"};
            case "not" -> new String[] {"type", "condition"};
            case "block_in_front" -> new String[] {"type", "block", "distance"};
            case "biome" -> new String[] {"type", "biome"};
            case "fluid_ahead" -> new String[] {"type"};
            case "distance_travelled" -> new String[] {"type", "blocks"};
            case "time_elapsed" -> new String[] {"type", "ticks"};
            default -> null;
        };
        if (keys == null || !onlyKeys(json, keys)) return false;
        String requiredString = switch (type) {
            case "has_item" -> "item";
            case "block_within" -> "block";
            case "dimension" -> "dimension";
            case "block_in_front" -> "block";
            case "biome" -> "biome";
            default -> null;
        };
        if (requiredString != null && strictString(json, requiredString) == null) return false;
        String requiredInteger = switch (type) {
            case "distance_travelled" -> "blocks";
            case "time_elapsed" -> "ticks";
            default -> null;
        };
        if (requiredInteger != null && integer(json.get(requiredInteger)) == null) return false;
        for (String key : new String[] {"count", "slots", "percent", "blocks", "radius", "distance"}) {
            if (json.has(key) && integer(json.get(key)) == null) return false;
        }
        return true;
    }

    private static List<Integer> parseAfter(JsonObject obj, String path, int localIndex, List<String> errors) {
        List<Integer> result = new ArrayList<>();
        if (!obj.has("after")) return result;
        JsonElement value = obj.get("after");
        if (!value.isJsonArray()) {
            errors.add(path(path, "after may only name earlier steps in this sequence"));
            return result;
        }
        JsonArray rows = value.getAsJsonArray();
        if (rows.size() > MAX_AFTER) errors.add(path(path, "at most " + MAX_AFTER + " dependencies"));
        for (int i = 0; i < Math.min(rows.size(), MAX_AFTER); i++) {
            Integer dependency = integer(rows.get(i));
            if (dependency == null || dependency < 1 || dependency > localIndex)
                errors.add(path(path, "after may only name earlier steps in this sequence"));
            else result.add(dependency);
        }
        return result;
    }

    private static JsonArray array(JsonElement value, String path, boolean requiredNonEmpty, List<String> errors) {
        if (value == null || !value.isJsonArray()) {
            errors.add(path(path, "must be an array"));
            return null;
        }
        JsonArray array = value.getAsJsonArray();
        if (requiredNonEmpty && array.isEmpty()) {
            errors.add(path(path, "must not be empty"));
            return null;
        }
        return array;
    }

    private static Integer strictInteger(
            JsonObject obj, String name, Integer fallback, String path, List<String> errors) {
        if (!obj.has(name)) return fallback;
        Integer result = integer(obj.get(name));
        if (result == null) errors.add(path(path, name + " must be an integer"));
        return result;
    }

    private static Integer integer(JsonElement element) {
        if (element == null
                || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isNumber()) return null;
        try {
            String raw = element.getAsString();
            if (raw.contains(".") || raw.contains("e") || raw.contains("E")) return null;
            return Integer.valueOf(raw);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String strictString(JsonObject obj, String name) {
        JsonElement value = obj.get(name);
        return value != null
                        && value.isJsonPrimitive()
                        && value.getAsJsonPrimitive().isString()
                ? value.getAsString()
                : null;
    }

    private static boolean onlyKeys(JsonObject object, String... keys) {
        if (object.size() > keys.length) return false;
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

    private static boolean errorsBelow(List<String> errors, String prefix) {
        return errors.stream().anyMatch(error -> error.startsWith(prefix + ":"));
    }

    private static String stepPath(String parent, int number) {
        return parent.isEmpty() ? "step " + number : parent + " #" + number;
    }

    private static String path(String path, String message) {
        return path + ": " + message;
    }

    public static Optional<FailurePolicy> policy(String text) {
        return switch (text) {
            case "ask" -> Optional.of(FailurePolicy.ASK);
            case "skip" -> Optional.of(FailurePolicy.SKIP);
            case "stop_plan" -> Optional.of(FailurePolicy.STOP_PLAN);
            default -> Optional.empty();
        };
    }

    private static final class Counter {
        private int argumentChars;
    }
}
