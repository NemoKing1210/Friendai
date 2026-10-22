package dev.friedai.tool;

import com.google.gson.JsonArray;
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
        validateObject("", schema, args, errors);
        return errors;
    }

    /** @param prefix path of the object being validated inside its parent, e.g. {@code ""} at the top level, {@code "steps[2]."} inside an array item */
    private static void validateObject(String prefix, JsonObject schema, JsonObject args, List<String> errors) {
        if (schema.has("required")) {
            for (JsonElement r : schema.getAsJsonArray("required")) {
                String name = r.getAsString();
                if (!args.has(name) || args.get(name).isJsonNull()) {
                    errors.add("missing required argument '" + prefix + name + "'");
                }
            }
        }
        if (schema.has("properties")) {
            for (Map.Entry<String, JsonElement> prop : schema.getAsJsonObject("properties").entrySet()) {
                JsonElement value = args.get(prop.getKey());
                if (value == null || value.isJsonNull()) {
                    continue;
                }
                checkProperty(prefix + prop.getKey(), prop.getValue().getAsJsonObject(), value, errors);
            }
        }
    }

    private static void checkProperty(String label, JsonObject spec, JsonElement value, List<String> errors) {
        String type = spec.has("type") ? spec.get("type").getAsString() : "";
        switch (type) {
            case "string" -> {
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                    errors.add("'" + label + "' must be a string");
                    return;
                }
            }
            case "integer" -> {
                if (!isNumber(value) || value.getAsDouble() != Math.rint(value.getAsDouble())) {
                    errors.add("'" + label + "' must be an integer");
                    return;
                }
            }
            case "number" -> {
                if (!isNumber(value)) {
                    errors.add("'" + label + "' must be a number");
                    return;
                }
            }
            case "boolean" -> {
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
                    errors.add("'" + label + "' must be a boolean");
                    return;
                }
            }
            case "array" -> {
                checkArray(label, spec, value, errors);
                return;
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
                errors.add("'" + label + "' must be one of " + allowed);
            }
        }
        if (isNumber(value)) {
            double d = value.getAsDouble();
            if (spec.has("minimum") && d < spec.get("minimum").getAsDouble()) {
                errors.add("'" + label + "' must be at least " + spec.get("minimum").getAsString());
            }
            if (spec.has("maximum") && d > spec.get("maximum").getAsDouble()) {
                errors.add("'" + label + "' must be at most " + spec.get("maximum").getAsString());
            }
        }
    }

    private static void checkArray(String label, JsonObject spec, JsonElement value, List<String> errors) {
        if (!value.isJsonArray()) {
            errors.add("'" + label + "' must be an array");
            return;
        }
        JsonArray array = value.getAsJsonArray();
        if (spec.has("minItems") && array.size() < spec.get("minItems").getAsInt()) {
            errors.add("'" + label + "' must have at least " + spec.get("minItems").getAsString() + " items");
        }
        if (spec.has("maxItems") && array.size() > spec.get("maxItems").getAsInt()) {
            errors.add("'" + label + "' must have at most " + spec.get("maxItems").getAsString() + " items");
        }
        if (!spec.has("items") || !spec.get("items").isJsonObject()) {
            return;
        }
        JsonObject itemSchema = spec.getAsJsonObject("items");
        boolean objectItems = itemSchema.has("type") && "object".equals(itemSchema.get("type").getAsString());
        for (int i = 0; i < array.size(); i++) {
            String itemLabel = label + "[" + (i + 1) + "]";
            JsonElement element = array.get(i);
            if (!element.isJsonObject()) {
                errors.add("'" + itemLabel + "' must be an object");
                continue;
            }
            if (objectItems) {
                validateObject(itemLabel + ".", itemSchema, element.getAsJsonObject(), errors);
            }
        }
    }

    private static boolean isNumber(JsonElement e) {
        return e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber();
    }
}
