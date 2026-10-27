package dev.friendai.agent.autonomy;

import com.google.gson.JsonObject;

/** One step of a reflex or fallback template: a plannable tool and its arguments. */
public record ReflexStep(String tool, JsonObject args) {
    public ReflexStep {
        args = args == null ? new JsonObject() : args.deepCopy();
    }

    @Override
    public JsonObject args() {
        return args.deepCopy();
    }
}
