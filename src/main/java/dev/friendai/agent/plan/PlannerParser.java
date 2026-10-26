package dev.friendai.agent.plan;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.friendai.tool.QueueParser;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Turns the planner's text into a validated {@link PlanProposal}, or a list of errors to show it on a retry. */
public final class PlannerParser {
    static final int MAX_NOTE = 240;

    private PlannerParser() {}

    public record Parsed(Optional<PlanProposal> proposal, List<String> errors) {
        public boolean ok() {
            return proposal.isPresent();
        }
    }

    /**
     * @param schemas the schemas of the tools the requester may plan with; any other tool is an error
     * @param allTools every plannable tool name, so an unknown name is reported as unknown
     */
    public static Parsed parse(
            String modelText, Map<String, JsonObject> schemas, Set<String> allTools, int maxSteps, int maxArgs,
            int maxTotal) {
        JsonObject root;
        try {
            root = JsonParser.parseString(extractObject(modelText)).getAsJsonObject();
        } catch (RuntimeException e) {
            return failed("the reply is not a JSON object");
        }
        if (!root.has("steps") || !root.get("steps").isJsonArray()) {
            return failed("the object needs a \"steps\" array");
        }
        JsonArray steps = normalize(root.getAsJsonArray("steps"));
        QueueParser.Result parsed = QueueParser.parse(steps, schemas, allTools, maxSteps, maxArgs, maxTotal);
        if (!parsed.ok()) {
            return new Parsed(Optional.empty(), parsed.errors());
        }
        return new Parsed(Optional.of(new PlanProposal(
                parsed.steps(), steps, note(root, "rationale"), note(root, "expected_outcome"))), List.of());
    }

    /** Fenced or chatty replies are tolerated: take everything from the first brace to the last. */
    static String extractObject(String text) {
        if (text == null) {
            return "";
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        return start >= 0 && end > start ? text.substring(start, end + 1) : text;
    }

    /** The queue wants args as a string; models often send an object, so accept both. */
    private static JsonArray normalize(JsonArray steps) {
        JsonArray out = new JsonArray();
        for (JsonElement element : steps) {
            if (element.isJsonObject()) {
                JsonObject step = element.getAsJsonObject().deepCopy();
                if (step.has("args") && step.get("args").isJsonObject()) {
                    step.addProperty("args", step.get("args").toString());
                }
                out.add(step);
            } else {
                out.add(element);
            }
        }
        return out;
    }

    private static String note(JsonObject root, String key) {
        if (!root.has(key) || !root.get(key).isJsonPrimitive()) {
            return "";
        }
        String text = root.get(key).getAsString().strip().replaceAll("\\s+", " ");
        return text.length() > MAX_NOTE ? text.substring(0, MAX_NOTE) : text;
    }

    private static Parsed failed(String error) {
        return new Parsed(Optional.empty(), List.of(error));
    }
}
