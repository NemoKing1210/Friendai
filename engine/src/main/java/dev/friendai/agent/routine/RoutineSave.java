package dev.friendai.agent.routine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.friendai.agent.routine.Routine.IntervalTrigger;
import dev.friendai.agent.routine.Routine.Outcome;
import dev.friendai.agent.routine.Routine.PhaseTrigger;
import dev.friendai.agent.routine.Routine.Step;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Bounded JSON payload for a {@link Routine}; the owner file supplies the format version. */
public final class RoutineSave {
    static final int MAX_INPUT = 32 * 1024;

    private RoutineSave() {}

    public static String encode(Routine routine) {
        Routine.validate(routine);
        JsonObject root = new JsonObject();
        root.addProperty("name", routine.name());
        root.add("trigger", trigger(routine.trigger()));
        JsonArray steps = new JsonArray();
        for (Step step : routine.steps()) {
            JsonObject o = new JsonObject();
            o.addProperty("tool", step.tool());
            o.add("args", step.args().deepCopy());
            steps.add(o);
        }
        root.add("steps", steps);
        root.addProperty("maxRuns", routine.maxRuns());
        root.addProperty("runs", routine.runs());
        root.addProperty("enabled", routine.enabled());
        root.addProperty("nextDue", routine.nextDueTick());
        root.addProperty("lastStarted", routine.lastStartedTick());
        root.addProperty("lastPlan", routine.lastPlanId());
        root.addProperty("outcome", routine.lastOutcome().name());
        root.addProperty("summary", routine.lastSummary());
        return root.toString();
    }

    /** The routine, or empty when the text is missing, oversized, broken or from an unsupported shape. */
    public static Optional<Routine> decode(String saved) {
        if (saved == null || saved.isBlank() || saved.length() > MAX_INPUT) {
            return Optional.empty();
        }
        try {
            JsonObject root = JsonParser.parseString(saved).getAsJsonObject();
            if (!root.has("trigger") || !root.has("steps")) {
                return Optional.empty();
            }
            JsonArray rows = root.getAsJsonArray("steps");
            if (rows.size() < 1 || rows.size() > Routine.MAX_STEPS) {
                return Optional.empty();
            }
            List<Step> steps = new ArrayList<>();
            for (JsonElement element : rows) {
                JsonObject row = element.getAsJsonObject();
                String tool = string(row, "tool");
                JsonObject args = row.has("args") && row.get("args").isJsonObject()
                        ? row.getAsJsonObject("args")
                        : new JsonObject();
                if (args.toString().length() > Routine.MAX_ARGS_CHARS) {
                    return Optional.empty();
                }
                steps.add(new Step(tool, args));
            }
            Routine routine = new Routine(
                    string(root, "name"),
                    trigger(root.getAsJsonObject("trigger")),
                    steps,
                    integer(root, "maxRuns", 0),
                    integer(root, "runs", 0),
                    !root.has("enabled") || root.get("enabled").getAsBoolean(),
                    longValue(root, "nextDue"),
                    longValue(root, "lastStarted"),
                    longValue(root, "lastPlan"),
                    outcome(root),
                    string(root, "summary"));
            Routine.validate(routine);
            return Optional.of(routine);
        } catch (RuntimeException broken) {
            return Optional.empty();
        }
    }

    private static JsonObject trigger(Routine.Trigger trigger) {
        JsonObject o = new JsonObject();
        switch (trigger) {
            case PhaseTrigger phase -> {
                o.addProperty("kind", "phase");
                o.addProperty("phase", phase.phase().name());
            }
            case IntervalTrigger interval -> {
                o.addProperty("kind", "interval");
                o.addProperty("minutes", interval.minutes());
            }
        }
        return o;
    }

    private static Routine.Trigger trigger(JsonObject o) {
        String kind = string(o, "kind");
        return switch (kind) {
            case "phase" -> new PhaseTrigger(Routine.Phase.valueOf(string(o, "phase")));
            case "interval" -> new IntervalTrigger(o.get("minutes").getAsInt());
            default -> throw new IllegalArgumentException("unknown routine trigger: " + kind);
        };
    }

    private static Outcome outcome(JsonObject root) {
        if (!root.has("outcome")) {
            return Outcome.NONE;
        }
        try {
            return Outcome.valueOf(root.get("outcome").getAsString());
        } catch (IllegalArgumentException unknown) {
            return Outcome.NONE;
        }
    }

    private static String string(JsonObject root, String key) {
        return root.has(key) && !root.get(key).isJsonNull() ? root.get(key).getAsString() : "";
    }

    private static int integer(JsonObject root, String key, int fallback) {
        return root.has(key) && !root.get(key).isJsonNull() ? root.get(key).getAsInt() : fallback;
    }

    private static long longValue(JsonObject root, String key) {
        return root.has(key) && !root.get(key).isJsonNull() ? root.get(key).getAsLong() : 0L;
    }
}
