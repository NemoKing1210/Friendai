package dev.friendai.agent.plan;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The bounded one-line-per-tool block of the planner prompt. Prose cannot make an enum or a bound unambiguous, so each
 * line carries the structural facts the strict parser will enforce: required markers, types, closed enum values, number
 * and length bounds, and the required fields of a nested object. Whole tools are omitted when the budget runs out, never
 * cut mid-signature, and the digest says how many were left out.
 */
public final class PlannerToolDigest {
    /** The digest never grows past this many characters, whatever the registry holds. */
    public static final int MAX_CHARS = 12_000;

    private static final int MAX_DESCRIPTION = 160;
    private static final int MAX_ENUM_VALUES = 12;
    private static final int MAX_PARAMETERS = 24;
    private static final int MAX_PARAMETER_CHARS = 600;
    private static final int MAX_NESTED_FIELDS = 8;
    /** Room kept for the omission line, so the digest never exceeds {@link #MAX_CHARS}. */
    private static final int TAIL_RESERVE = 120;

    /** One plannable tool as the input factory knows it; the schema is the same object the parser validates against. */
    public record Tool(String name, String description, JsonObject schema) {}

    private PlannerToolDigest() {}

    /** Every tool that fits, in the given order, and one line naming how many did not. */
    public static String build(List<Tool> tools) {
        StringBuilder out = new StringBuilder();
        int budget = MAX_CHARS - TAIL_RESERVE;
        int omitted = 0;
        for (Tool tool : tools) {
            String line = describe(tool) + "\n";
            if (out.length() + line.length() > budget) {
                omitted++;
                continue;
            }
            out.append(line);
        }
        if (omitted > 0) {
            out.append("- ")
                    .append(omitted)
                    .append(" more tools are omitted for length; plan only with the tools listed above.\n");
        }
        return out.toString();
    }

    public static String describe(Tool tool) {
        String description = firstSentence(tool.description());
        return "- " + tool.name() + "(" + parameters(tool.schema()) + ")"
                + (description.isEmpty() ? "" : ": " + description);
    }

    private static String parameters(JsonObject schema) {
        JsonObject properties = object(schema, "properties");
        if (properties == null) {
            return "";
        }
        Set<String> required = strings(schema, "required");
        List<String> rendered = new ArrayList<>();
        int length = 0;
        for (String name : properties.keySet()) {
            if (rendered.size() == MAX_PARAMETERS || length >= MAX_PARAMETER_CHARS) {
                rendered.add("…");
                break;
            }
            String parameter = parameter(name, object(properties, name), required.contains(name), 0);
            rendered.add(parameter);
            length += parameter.length() + 2;
        }
        return String.join(", ", rendered);
    }

    private static String parameter(String name, JsonObject property, boolean required, int depth) {
        return name + (required ? "*" : "") + ": " + type(property, depth);
    }

    private static String type(JsonObject property, int depth) {
        if (property == null) {
            return "any";
        }
        String name = string(property, "type");
        String type = name == null || name.isBlank() ? "any" : name;
        StringBuilder out = new StringBuilder(type);
        switch (type) {
            case "string" -> {
                enumValues(property, out);
                range(property, "minLength", "maxLength", "", out);
            }
            case "integer", "number" -> range(property, "minimum", "maximum", "", out);
            case "array" -> {
                JsonObject items = object(property, "items");
                if (items != null && depth < 2) {
                    out.append('<').append(type(items, depth + 1)).append('>');
                }
                range(property, "minItems", "maxItems", " items", out);
            }
            case "object" -> nestedFields(property, depth, out);
            default -> {}
        }
        return out.toString();
    }

    private static void enumValues(JsonObject property, StringBuilder out) {
        JsonElement raw = property.get("enum");
        if (raw == null || !raw.isJsonArray()) {
            return;
        }
        var values = raw.getAsJsonArray();
        out.append('[');
        for (int i = 0; i < Math.min(MAX_ENUM_VALUES, values.size()); i++) {
            if (i > 0) {
                out.append('|');
            }
            out.append(values.get(i).getAsString());
        }
        if (values.size() > MAX_ENUM_VALUES) {
            out.append("|…");
        }
        out.append(']');
    }

    private static void nestedFields(JsonObject schema, int depth, StringBuilder out) {
        JsonObject properties = object(schema, "properties");
        if (depth >= 2 || properties == null || properties.isEmpty()) {
            out.append("{…}");
            return;
        }
        Set<String> required = strings(schema, "required");
        out.append('{');
        int shown = 0;
        for (String name : properties.keySet()) {
            if (shown == MAX_NESTED_FIELDS) {
                out.append(", …");
                break;
            }
            if (shown > 0) {
                out.append(", ");
            }
            out.append(parameter(name, object(properties, name), required.contains(name), depth + 1));
            shown++;
        }
        out.append('}');
    }

    /** Appends {@code 1..64} (or {@code len 1..64} for lengths) when the schema names a bound. */
    private static void range(JsonObject property, String minKey, String maxKey, String label, StringBuilder out) {
        String min = number(property, minKey);
        String max = number(property, maxKey);
        if (min == null && max == null) {
            return;
        }
        out.append(label)
                .append(' ')
                .append(min == null ? "…" : min)
                .append("..")
                .append(max == null ? "…" : max);
    }

    private static String firstSentence(String description) {
        String text = description == null ? "" : description.strip();
        int stop = text.indexOf(". ");
        if (stop > 0) {
            text = text.substring(0, stop + 1);
        }
        return text.length() > MAX_DESCRIPTION ? text.substring(0, MAX_DESCRIPTION) : text;
    }

    private static JsonObject object(JsonObject parent, String name) {
        JsonElement element = parent == null ? null : parent.get(name);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static String string(JsonObject parent, String name) {
        JsonElement element = parent == null ? null : parent.get(name);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
    }

    private static String number(JsonObject parent, String name) {
        JsonElement element = parent == null ? null : parent.get(name);
        if (element == null || !element.isJsonPrimitive()) {
            return null;
        }
        double value = element.getAsDouble();
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    private static Set<String> strings(JsonObject parent, String name) {
        JsonElement element = parent == null ? null : parent.get(name);
        if (element == null || !element.isJsonArray()) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (JsonElement item : element.getAsJsonArray()) {
            if (item.isJsonPrimitive()) {
                out.add(item.getAsString());
            }
        }
        return out;
    }
}
