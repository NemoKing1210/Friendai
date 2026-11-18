package dev.friendai.execution.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** Validates the JSON-schema subset used by tools. Open objects and optional nulls retain their legacy behavior. */
public final class ArgValidator {
    private ArgValidator() {}

    public static List<String> validate(JsonObject schema, JsonObject args) {
        List<String> errors = new ArrayList<>();
        validateObject("", schema, args, errors);
        return errors;
    }

    /** A pure normalizer for optional fields that must be supplied as a group. Null means omitted. */
    public static JsonObject requireTogether(JsonObject args, String... fields) {
        int present = 0;
        for (String field : fields) {
            if (args.has(field) && !args.get(field).isJsonNull()) present++;
        }
        if (present != 0 && present != fields.length) {
            throw new IllegalArgumentException("give all of " + String.join(", ", fields) + " or none");
        }
        return args;
    }

    /** @param prefix path of the object being validated inside its parent, e.g. {@code ""} at the top level, {@code "steps[2]."} inside an array item */
    private static void validateObject(String prefix, JsonObject schema, JsonObject args, List<String> errors) {
        JsonObject properties = schema.has("properties") ? schema.getAsJsonObject("properties") : new JsonObject();
        if (schema.has("required")) {
            for (JsonElement r : schema.getAsJsonArray("required")) {
                String name = r.getAsString();
                if (!args.has(name) || (args.get(name).isJsonNull() && !declaresNull(properties.get(name)))) {
                    errors.add("missing required argument '" + prefix + name + "'");
                }
            }
        }
        for (Map.Entry<String, JsonElement> prop : properties.entrySet()) {
            JsonElement value = args.get(prop.getKey());
            if (value == null || (value.isJsonNull() && !declaresNull(prop.getValue()))) continue;
            checkProperty(prefix + prop.getKey(), prop.getValue().getAsJsonObject(), value, errors);
        }
        if (schema.has("additionalProperties")
                && schema.get("additionalProperties").isJsonPrimitive()
                && schema.getAsJsonPrimitive("additionalProperties").isBoolean()
                && !schema.get("additionalProperties").getAsBoolean()) {
            for (String name : args.keySet()) {
                if (!properties.has(name)) errors.add("unknown argument '" + prefix + name + "'");
            }
        }
    }

    static boolean declaresNull(JsonElement schema) {
        if (schema == null || !schema.isJsonObject()) return false;
        JsonObject spec = schema.getAsJsonObject();
        return (spec.has("type") && "null".equals(spec.get("type").getAsString()))
                || (spec.has("enum")
                        && spec.getAsJsonArray("enum").asList().stream().anyMatch(JsonElement::isJsonNull))
                || (spec.has("oneOf")
                        && spec.getAsJsonArray("oneOf").asList().stream().anyMatch(ArgValidator::declaresNull));
    }

    private static void checkProperty(String label, JsonObject spec, JsonElement value, List<String> errors) {
        if (spec.has("oneOf") && spec.get("oneOf").isJsonArray()) {
            int matches = 0;
            for (JsonElement candidate : spec.getAsJsonArray("oneOf")) {
                if (!candidate.isJsonObject()) continue;
                List<String> candidateErrors = new ArrayList<>();
                checkProperty(label, candidate.getAsJsonObject(), value, candidateErrors);
                if (candidateErrors.isEmpty()) matches++;
            }
            if (matches != 1) {
                errors.add("'" + label + "' must match one supported shape");
                return;
            }
        }
        String type = spec.has("type") ? spec.get("type").getAsString() : "";
        BigDecimal number = null;
        switch (type) {
            case "string" -> {
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                    errors.add("'" + label + "' must be a string");
                    return;
                }
                String text = value.getAsString();
                int length = text.codePointCount(0, text.length());
                if (spec.has("minLength") && length < spec.get("minLength").getAsInt()) {
                    errors.add("'" + label + "' is shorter than "
                            + spec.get("minLength").getAsInt() + " characters");
                }
                if (spec.has("maxLength") && length > spec.get("maxLength").getAsInt()) {
                    errors.add("'" + label + "' is longer than "
                            + spec.get("maxLength").getAsInt() + " characters");
                }
                if (spec.has("pattern")
                        && !Pattern.compile(spec.get("pattern").getAsString())
                                .matcher(text)
                                .find()) {
                    errors.add("'" + label + "' does not match the required format");
                }
                if (spec.has("format") && "uuid".equals(spec.get("format").getAsString()) && !isUuid(text)) {
                    errors.add("'" + label + "' must be a UUID");
                }
            }
            case "integer" -> {
                number = finiteNumber(value);
                if (number == null || number.stripTrailingZeros().scale() > 0) {
                    errors.add("'" + label + "' must be an integer");
                    return;
                }
            }
            case "number" -> {
                number = finiteNumber(value);
                if (number == null) {
                    errors.add("'" + label + "' must be a " + (isNumber(value) ? "finite number" : "number"));
                    return;
                }
            }
            case "boolean" -> {
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
                    errors.add("'" + label + "' must be a boolean");
                    return;
                }
            }
            case "null" -> {
                if (!value.isJsonNull()) {
                    errors.add("'" + label + "' must be null");
                    return;
                }
            }
            case "object", "" -> {
                boolean object = "object".equals(type)
                        || spec.has("properties")
                        || spec.has("required")
                        || spec.has("additionalProperties");
                if (object) {
                    if (!value.isJsonObject()) {
                        errors.add("'" + label + "' must be an object");
                        return;
                    }
                    validateObject(label + ".", spec, value.getAsJsonObject(), errors);
                }
            }
            case "array" -> checkArray(label, spec, value, errors);
            default -> {}
        }
        if (spec.has("enum")) {
            boolean found = false;
            List<String> allowed = new ArrayList<>();
            for (JsonElement e : spec.getAsJsonArray("enum")) {
                allowed.add(e.isJsonPrimitive() ? e.getAsString() : e.toString());
                found |= e.equals(value);
            }
            if (!found) {
                errors.add("'" + label + "' must be one of " + allowed);
            }
        }
        if (number != null) {
            if (spec.has("minimum") && number.compareTo(spec.get("minimum").getAsBigDecimal()) < 0) {
                errors.add("'" + label + "' must be at least "
                        + spec.get("minimum").getAsString());
            }
            if (spec.has("maximum") && number.compareTo(spec.get("maximum").getAsBigDecimal()) > 0) {
                errors.add(
                        "'" + label + "' must be at most " + spec.get("maximum").getAsString());
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
            errors.add(
                    "'" + label + "' must have at least " + spec.get("minItems").getAsString() + " items");
        }
        if (spec.has("maxItems") && array.size() > spec.get("maxItems").getAsInt()) {
            errors.add(
                    "'" + label + "' must have at most " + spec.get("maxItems").getAsString() + " items");
        }
        if (!spec.has("items") || !spec.get("items").isJsonObject()) {
            return;
        }
        JsonObject itemSchema = spec.getAsJsonObject("items");
        for (int i = 0; i < array.size(); i++) {
            String itemLabel = label + "[" + (i + 1) + "]";
            checkProperty(itemLabel, itemSchema, array.get(i), errors);
        }
    }

    private static boolean isNumber(JsonElement e) {
        return e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber();
    }

    private static BigDecimal finiteNumber(JsonElement value) {
        if (!isNumber(value) || !Double.isFinite(value.getAsDouble())) return null;
        try {
            return value.getAsBigDecimal();
        } catch (NumberFormatException unsupported) {
            return null;
        }
    }

    private static boolean isUuid(String text) {
        if (text.length() != 36) return false;
        try {
            return UUID.fromString(text).toString().equalsIgnoreCase(text);
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }
}
