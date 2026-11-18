package dev.friendai.execution.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.math.BigDecimal;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Repairs scalars a model wrote as text. A model that calls a tool from memory, without the schema in front of it,
 * tends to quote its numbers and flags ({@code "count": "2"}, {@code "collect": "true"}). The value is unambiguous,
 * so refusing it only costs another request and usually the same mistake again.
 *
 * <p>Only a top-level property whose schema names {@code integer}, {@code number} or {@code boolean} is touched, and
 * only when the whole string is that value. Optional nulls without an explicit null type are omitted at any depth.
 * Wrong types still reach {@link ArgValidator}.
 */
public final class ArgCoercion {
    private static final Pattern INTEGER = Pattern.compile("-?\\d{1,18}");
    private static final Pattern NUMBER = Pattern.compile("-?\\d{1,18}(\\.\\d{1,18})?");

    private ArgCoercion() {}

    /** Repairs the arguments without mutating the caller's object. */
    public static JsonObject coerce(JsonObject schema, JsonObject args) {
        return schema == null || args == null ? args : repairObject(schema, args, true);
    }

    private static JsonObject repairObject(JsonObject schema, JsonObject args, boolean coerceScalars) {
        JsonElement properties = schema.get("properties");
        if (properties == null || !properties.isJsonObject()) {
            return args;
        }
        JsonObject repaired = null;
        for (Map.Entry<String, JsonElement> property :
                properties.getAsJsonObject().entrySet()) {
            String name = property.getKey();
            JsonElement value = args.get(name);
            if (value == null || !property.getValue().isJsonObject()) {
                continue;
            }
            JsonObject spec = property.getValue().getAsJsonObject();
            JsonElement replacement = value;
            if (value.isJsonNull() && !ArgValidator.declaresNull(spec) && !required(schema, name)) {
                replacement = null;
            } else if (coerceScalars
                    && value.isJsonPrimitive()
                    && value.getAsJsonPrimitive().isString()) {
                JsonPrimitive typed = typed(spec, value.getAsString().strip());
                replacement = typed == null ? value : typed;
            } else {
                replacement = repairNested(spec, value);
            }
            if (replacement == value) {
                continue;
            }
            if (repaired == null) {
                repaired = args.deepCopy();
            }
            if (replacement == null) {
                repaired.remove(name);
            } else {
                repaired.add(name, replacement);
            }
        }
        return repaired == null ? args : repaired;
    }

    private static boolean required(JsonObject schema, String name) {
        JsonElement required = schema.get("required");
        return required != null
                && required.isJsonArray()
                && required.getAsJsonArray().contains(new JsonPrimitive(name));
    }

    private static JsonElement repairNested(JsonObject schema, JsonElement value) {
        if (!value.isJsonObject() && !value.isJsonArray()) {
            return value;
        }
        JsonElement options = schema.get("oneOf");
        if (options != null && options.isJsonArray()) {
            String type = value.isJsonObject() ? "object" : "array";
            JsonObject match = null;
            for (JsonElement option : options.getAsJsonArray()) {
                if (option.isJsonObject()
                        && new JsonPrimitive(type)
                                .equals(option.getAsJsonObject().get("type"))) {
                    if (match != null) {
                        return value;
                    }
                    match = option.getAsJsonObject();
                }
            }
            return match == null ? value : repairNested(match, value);
        }
        if (value.isJsonObject()) {
            return repairObject(schema, value.getAsJsonObject(), false);
        }
        JsonElement items = schema.get("items");
        if (items == null || !items.isJsonObject()) {
            return value;
        }
        JsonArray original = value.getAsJsonArray();
        JsonArray repaired = null;
        for (int i = 0; i < original.size(); i++) {
            JsonElement item = original.get(i);
            JsonElement replacement = repairNested(items.getAsJsonObject(), item);
            if (replacement != item) {
                if (repaired == null) {
                    repaired = original.deepCopy();
                }
                repaired.set(i, replacement);
            }
        }
        return repaired == null ? value : repaired;
    }

    private static JsonPrimitive typed(JsonObject spec, String text) {
        if (!spec.has("type") || !spec.get("type").isJsonPrimitive()) {
            return null;
        }
        return switch (spec.get("type").getAsString()) {
            case "integer" -> INTEGER.matcher(text).matches() ? new JsonPrimitive(Long.parseLong(text)) : null;
            case "number" -> NUMBER.matcher(text).matches() ? new JsonPrimitive(new BigDecimal(text)) : null;
            case "boolean" ->
                text.equalsIgnoreCase("true")
                        ? new JsonPrimitive(true)
                        : text.equalsIgnoreCase("false") ? new JsonPrimitive(false) : null;
            default -> null;
        };
    }
}
