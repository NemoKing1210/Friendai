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
        JsonElement extracted = extract(modelText);
        if (extracted == null) {
            return failed("the reply is not a JSON object");
        }
        JsonObject root = extracted.isJsonArray() ? steps(extracted.getAsJsonArray()) : extracted.getAsJsonObject();
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

    /**
     * Fenced or chatty replies are tolerated: the whole text, a fenced object, a bare array of steps or an object
     * followed by an example all yield the container the model meant.
     */
    static JsonElement extract(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        JsonElement whole = tryParse(trimmed);
        if (whole != null && (whole.isJsonObject() || whole.isJsonArray())) {
            return whole;
        }
        JsonElement firstObject = null;
        JsonElement firstArray = null;
        for (int i = 0; i < text.length(); i++) {
            char open = text.charAt(i);
            if (open != '{' && open != '[') {
                continue;
            }
            JsonElement candidate = tryParse(balanced(text, i, open, open == '{' ? '}' : ']'));
            if (candidate == null) {
                continue;
            }
            if (candidate.isJsonObject()) {
                if (candidate.getAsJsonObject().has("steps")) {
                    return candidate;
                }
                if (firstObject == null) {
                    firstObject = candidate;
                }
            } else if (candidate.isJsonArray()) {
                if (containsObjects(candidate.getAsJsonArray())) {
                    return candidate;
                }
                if (firstArray == null) {
                    firstArray = candidate;
                }
            }
        }
        return firstObject != null ? firstObject : firstArray;
    }

    /** The container that starts at {@code start}, with string literals and escapes respected. */
    private static String balanced(String text, int start, char open, char close) {
        int depth = 0;
        boolean quoted = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    quoted = false;
                }
                continue;
            }
            if (c == '"') {
                quoted = true;
            } else if (c == open) {
                depth++;
            } else if (c == close && --depth == 0) {
                return text.substring(start, i + 1);
            }
        }
        return text.substring(start);
    }

    private static JsonElement tryParse(String json) {
        try {
            return JsonParser.parseString(json);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean containsObjects(JsonArray array) {
        if (array.isEmpty()) {
            return false;
        }
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                return false;
            }
        }
        return true;
    }

    /** A bare array of steps, the shape a model sometimes answers with, becomes the usual root object. */
    private static JsonObject steps(JsonArray array) {
        JsonObject root = new JsonObject();
        root.add("steps", array);
        return root;
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
