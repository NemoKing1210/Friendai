package dev.friendai.task.plan;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The wire form of a plan's remaining work, stored by {@link SequenceTask#saveData()} so a reload can resume it.
 * The text is {@code plan:} followed by one JSON object; a malformed string is simply not restored.
 */
public final class PlanSave {
    /** What a plan's saved text starts with; anything else is left to the other task types. */
    public static final String PREFIX = "plan:";

    private PlanSave() {}

    /** One step as it is stored: the tool it came from and the arguments it was started with. */
    public record Step(String tool, JsonObject args) {}

    /** Where the plan is, what it has done, and the step that is running, if any. */
    public record Saved(String goal, int index, List<String> done, String active, List<Step> steps) {}

    public static String encode(String goal, int index, List<String> done, String active, List<Step> steps) {
        JsonObject json = new JsonObject();
        if (goal != null) {
            json.addProperty("goal", goal);
        }
        json.addProperty("index", index);
        JsonArray doneArray = new JsonArray();
        done.forEach(doneArray::add);
        json.add("done", doneArray);
        if (active != null) {
            json.addProperty("active", active);
        }
        JsonArray stepsArray = new JsonArray();
        for (Step step : steps) {
            JsonObject stepJson = new JsonObject();
            stepJson.addProperty("tool", step.tool());
            stepJson.add("args", step.args() == null ? new JsonObject() : step.args());
            stepsArray.add(stepJson);
        }
        json.add("steps", stepsArray);
        return PREFIX + json;
    }

    /** The plan in {@code saved}, or empty when it is another kind of task or the text is broken. */
    public static Optional<Saved> decode(String saved) {
        if (saved == null || !saved.startsWith(PREFIX)) {
            return Optional.empty();
        }
        try {
            JsonElement parsed = JsonParser.parseString(saved.substring(PREFIX.length()));
            if (!parsed.isJsonObject()) {
                return Optional.empty();
            }
            JsonObject json = parsed.getAsJsonObject();
            int index = json.get("index").getAsInt();
            String goal = string(json, "goal");
            String active = string(json, "active");
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
                steps.add(new Step(tool, args == null || args.isJsonNull()
                        ? new JsonObject()
                        : args.getAsJsonObject()));
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
