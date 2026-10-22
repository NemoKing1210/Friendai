package dev.friedai.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates the {@code steps} array of the {@code run_plan} tool: shapes, tool names and step arguments.
 * Only syntax and argument shape are checked here — whether a step can succeed is decided when it starts,
 * because the world changes while the plan runs.
 */
public final class PlanParser {
    public static final int MAX_STEPS = 16;

    private PlanParser() {}

    /** @param stepSchemas parameter schema of every tool that may appear in a plan, by name
     *  @param allTools every registered tool name, so a non-plannable tool gets its own message */
    public static Result parse(JsonArray steps, Map<String, JsonObject> stepSchemas, Set<String> allTools) {
        if (steps.size() < 1) {
            return new Result(List.of(), List.of("a plan needs at least 1 step"));
        }
        if (steps.size() > MAX_STEPS) {
            return new Result(List.of(), List.of("a plan can have at most " + MAX_STEPS + " steps"));
        }
        List<PlanStep> parsed = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            String prefix = "step " + (i + 1) + ": ";
            JsonElement element = steps.get(i);
            if (!element.isJsonObject()) {
                errors.add(prefix + "must be an object with a 'tool' string");
                continue;
            }
            JsonObject step = element.getAsJsonObject();
            JsonElement toolElement = step.get("tool");
            if (toolElement == null || !toolElement.isJsonPrimitive() || !toolElement.getAsJsonPrimitive().isString()) {
                errors.add(prefix + "must be an object with a 'tool' string");
                continue;
            }
            String tool = toolElement.getAsString();
            JsonObject schema = stepSchemas.get(tool);
            if (schema == null) {
                errors.add(allTools.contains(tool)
                        ? prefix + "'" + tool + "' cannot be used in a plan"
                        : prefix + "unknown tool '" + tool + "'");
                continue;
            }
            JsonObject args = new JsonObject();
            JsonElement argsElement = step.get("args");
            if (argsElement != null && !argsElement.isJsonNull()) {
                if (!argsElement.isJsonPrimitive() || !argsElement.getAsJsonPrimitive().isString()) {
                    errors.add(prefix + "args is not a JSON object: got " + argsElement);
                    continue;
                }
                String raw = argsElement.getAsString();
                if (!raw.isBlank()) {
                    JsonElement parsedArgs;
                    try {
                        parsedArgs = JsonParser.parseString(raw);
                    } catch (JsonParseException | IllegalStateException e) {
                        errors.add(prefix + "args is not a JSON object: " + e.getMessage());
                        continue;
                    }
                    if (!parsedArgs.isJsonObject()) {
                        errors.add(prefix + "args is not a JSON object: got " + parsedArgs);
                        continue;
                    }
                    args = parsedArgs.getAsJsonObject();
                }
            }
            for (String problem : ArgValidator.validate(schema, args)) {
                errors.add(prefix + problem);
            }
            parsed.add(new PlanStep(tool, args));
        }
        if (!errors.isEmpty()) {
            return new Result(List.of(), errors);
        }
        return new Result(parsed, List.of());
    }

    public record Result(List<PlanStep> steps, List<String> errors) {
        public boolean ok() {
            return errors.isEmpty();
        }
    }
}
