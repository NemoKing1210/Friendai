package dev.friendai.execution.tool;

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
 * only when the whole string is that value. Everything else reaches {@link ArgValidator} unchanged, so a wrong type
 * is still an error result.
 */
public final class ArgCoercion {
    private static final Pattern INTEGER = Pattern.compile("-?\\d{1,18}");
    private static final Pattern NUMBER = Pattern.compile("-?\\d{1,18}(\\.\\d{1,18})?");

    private ArgCoercion() {}

    /** The arguments with quoted scalars unquoted, or the input itself when nothing needed repair. Never mutates it. */
    public static JsonObject coerce(JsonObject schema, JsonObject args) {
        if (schema == null
                || args == null
                || !schema.has("properties")
                || !schema.get("properties").isJsonObject()) {
            return args;
        }
        JsonObject repaired = null;
        for (Map.Entry<String, JsonElement> property :
                schema.getAsJsonObject("properties").entrySet()) {
            JsonElement value = args.get(property.getKey());
            if (value == null
                    || !value.isJsonPrimitive()
                    || !value.getAsJsonPrimitive().isString()
                    || !property.getValue().isJsonObject()) {
                continue;
            }
            JsonPrimitive typed = typed(
                    property.getValue().getAsJsonObject(), value.getAsString().strip());
            if (typed == null) {
                continue;
            }
            if (repaired == null) {
                repaired = args.deepCopy();
            }
            repaired.add(property.getKey(), typed);
        }
        return repaired == null ? args : repaired;
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
