package dev.friedai.tool;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Validates tool arguments against the small JSON-schema subset used by the tools. Extra fields are allowed. */
public final class ArgValidator {
    private ArgValidator() {}

    public static List<String> validate(JsonObject schema, JsonObject args) {
        List<String> errors = new ArrayList<>();
        if (schema.has("required")) {
            for (JsonElement r : schema.getAsJsonArray("required")) {
                String name = r.getAsString();
                if (!args.has(name) || args.get(name).isJsonNull()) {
                    errors.add("missing required argument '" + name + "'");
                }
            }
        }
        if (schema.has("properties")) {
            for (Map.Entry<String, JsonElement> prop : schema.getAsJsonObject("properties").entrySet()) {
                JsonElement value = args.get(prop.getKey());
                if (value == null || value.isJsonNull()) {
                    continue;
                }
                checkProperty(prop.getKey(), prop.getValue().getAsJsonObject(), value, errors);
            }
        }
        return errors;
    }

    private static void checkProperty(String name, JsonObject spec, JsonElement value, List<String> errors) {
        String type = spec.has("type") ? spec.get("type").getAsString() : "";
        switch (type) {
            case "string" -> {
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                    errors.add("'" + name + "' must be a string");
                    return;
                }
            }
            case "integer" -> {
                if (!isNumber(value) || value.getAsDouble() != Math.rint(value.getAsDouble())) {
                    errors.add("'" + name + "' must be an integer");
                    return;
                }
            }
            case "number" -> {
                if (!isNumber(value)) {
                    errors.add("'" + name + "' must be a number");
                    return;
                }
            }
            case "boolean" -> {
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
                    errors.add("'" + name + "' must be a boolean");
                    return;
                }
            }
            default -> {
                return;
            }
        }
        if (spec.has("enum")) {
            boolean found = false;
            List<String> allowed = new ArrayList<>();
            for (JsonElement e : spec.getAsJsonArray("enum")) {
                allowed.add(e.getAsString());
                found |= e.equals(value);
            }
            if (!found) {
                errors.add("'" + name + "' must be one of " + allowed);
            }
        }
        if (isNumber(value)) {
            double d = value.getAsDouble();
            if (spec.has("minimum") && d < spec.get("minimum").getAsDouble()) {
                errors.add("'" + name + "' must be at least " + spec.get("minimum").getAsString());
            }
            if (spec.has("maximum") && d > spec.get("maximum").getAsDouble()) {
                errors.add("'" + name + "' must be at most " + spec.get("maximum").getAsString());
            }
        }
    }

    private static boolean isNumber(JsonElement e) {
        return e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber();
    }
}
