package dev.friendai.task.plan;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.friendai.task.TaskState;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The JSON payload of a plan's remaining work, nested in its typed {@code run_plan} task checkpoint.
 */
public final class PlanSave {
    private PlanSave() {}

    /** One step as it is stored: the tool it came from and the arguments it was started with. */
    public record Step(String tool, JsonObject args, UUID principal) {
        public Step(String tool, JsonObject args) {
            this(tool, args, null);
        }
    }

    /** Where the plan is, what it has done, and the step that is running, if any. */
    public record Saved(String goal, int index, List<String> done, TaskState active, List<Step> steps) {}

    public static String encode(String goal, int index, List<String> done, TaskState active, List<Step> steps) {
        JsonObject json = new JsonObject();
        if (goal != null) {
            json.addProperty("goal", goal);
        }
        json.addProperty("index", index);
        JsonArray doneArray = new JsonArray();
        done.forEach(doneArray::add);
        json.add("done", doneArray);
        if (active != null) {
            JsonObject task = new JsonObject();
            task.addProperty("type", active.type());
            task.addProperty("data", active.data());
            json.add("activeTask", task);
        }
        JsonArray stepsArray = new JsonArray();
        for (Step step : steps) {
            JsonObject stepJson = new JsonObject();
            stepJson.addProperty("tool", step.tool());
            stepJson.add("args", step.args() == null ? new JsonObject() : step.args());
            if (step.principal() != null)
                stepJson.addProperty("principal", step.principal().toString());
            stepsArray.add(stepJson);
        }
        json.add("steps", stepsArray);
        return json.toString();
    }

    /** The plan in {@code saved}, or empty when it is another kind of task or the text is broken. */
    public static Optional<Saved> decode(String saved) {
        if (saved == null || saved.length() > 8_192) return Optional.empty();
        try {
            JsonElement parsed = JsonParser.parseString(saved);
            if (!parsed.isJsonObject()) {
                return Optional.empty();
            }
            JsonObject json = parsed.getAsJsonObject();
            int index = json.get("index").getAsInt();
            String goal = string(json, "goal");
            TaskState active = null;
            if (json.has("activeTask")) {
                JsonObject task = json.getAsJsonObject("activeTask");
                active = new TaskState(string(task, "type"), string(task, "data"));
            }
            List<String> done = new ArrayList<>();
            for (JsonElement element : json.getAsJsonArray("done")) {
                done.add(element.getAsString());
            }
            List<Step> steps = new ArrayList<>();
            for (JsonElement element : json.getAsJsonArray("steps")) {
                JsonObject step = element.getAsJsonObject();
                String tool = step.get("tool").getAsString();
                if (tool.isBlank()) {
                    return Optional.empty();
                }
                JsonElement args = step.get("args");
                if (args != null && !args.isJsonNull() && !args.isJsonObject()) {
                    return Optional.empty();
                }
                UUID principal = null;
                if (step.has("principal") && !step.get("principal").isJsonNull()) {
                    principal = UUID.fromString(step.get("principal").getAsString());
                }
                steps.add(new Step(
                        tool,
                        args == null || args.isJsonNull() ? new JsonObject() : args.getAsJsonObject(),
                        principal));
            }
            if (steps.isEmpty() || index < 0 || index > steps.size()) {
                return Optional.empty();
            }
            return Optional.of(new Saved(goal, index, List.copyOf(done), active, List.copyOf(steps)));
        } catch (RuntimeException e) {
            // A broken field, missing array or wrong type: not a plan we can resume.
            return Optional.empty();
        }
    }

    private static String string(JsonObject json, String name) {
        JsonElement element = json.get(name);
        return element == null || element.isJsonNull() ? null : element.getAsString();
    }
}
