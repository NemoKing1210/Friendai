package dev.friendai.presentation.companion;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Turns the raw JSON arguments of a queued task into a short list of typed chips the menu can draw: an item or block
 * with its icon, a coordinate triple, a count, or a plain {@code key: value} pair. Pure text in, pure data out; the
 * client resolves {@link Chip#itemId()} to an icon and a localized name.
 */
public final class TaskArgs {
    private static final int MAX_CHIPS = 12;
    private static final int MAX_VALUE_CHARS = 40;
    private static final Set<String> COUNT_KEYS = Set.of("count", "amount", "quantity", "n");
    private static final Pattern RESOURCE_ID = Pattern.compile("(?:[a-z0-9_.-]+:)?[a-z0-9_./-]+");

    private TaskArgs() {}

    /** What a chip stands for. */
    public enum Kind {
        /** An item or block id; {@link Chip#text()} is the id, to be shown by its game name. */
        ITEM,
        /** Block coordinates; {@link Chip#text()} is {@code "x y z"}. */
        POS,
        /** A quantity; {@link Chip#text()} is the number. */
        COUNT,
        /** Anything else; {@link Chip#label()} is the argument name, {@link Chip#text()} its value. */
        TEXT
    }

    /** One drawable fragment. {@code label} is empty unless the chip names its argument. */
    public record Chip(Kind kind, String label, String text) {
        public Chip {
            label = label == null ? "" : label;
            text = text == null ? "" : text;
        }

        /** The id of an {@link Kind#ITEM} chip with the default namespace made explicit. */
        public String itemId() {
            return kind == Kind.ITEM ? withNamespace(text) : "";
        }
    }

    /** The chips for one task's arguments, empty for {@code {}}, blank or unparsable input. */
    public static List<Chip> parse(String rawArgs) {
        JsonObject object = asObject(rawArgs);
        if (object == null || object.isEmpty()) {
            return List.of();
        }
        List<Chip> chips = new ArrayList<>();
        Chip pos = position(object);
        for (var entry : object.entrySet()) {
            String key = entry.getKey();
            if (pos != null && (key.equals("x") || key.equals("y") || key.equals("z"))) {
                continue;
            }
            addValue(chips, key, entry.getValue());
        }
        if (pos != null) {
            chips.add(0, pos);
        }
        // Resources first, then where, then how many, then the rest.
        chips.sort((a, b) -> Integer.compare(rank(a.kind()), rank(b.kind())));
        return chips.size() > MAX_CHIPS ? List.copyOf(chips.subList(0, MAX_CHIPS)) : List.copyOf(chips);
    }

    /** A tool name for people: {@code mine_block_at} becomes {@code Mine block at}. */
    public static String title(String tool) {
        if (tool == null || tool.isBlank()) {
            return "";
        }
        String spaced = tool.replace('_', ' ').trim();
        return spaced.substring(0, 1).toUpperCase(Locale.ROOT) + spaced.substring(1);
    }

    /** {@code dirt} becomes {@code minecraft:dirt}; an id that already has a namespace is returned as is. */
    public static String withNamespace(String id) {
        return id.indexOf(':') >= 0 ? id : "minecraft:" + id;
    }

    private static int rank(Kind kind) {
        return switch (kind) {
            case ITEM -> 0;
            case POS -> 1;
            case COUNT -> 2;
            case TEXT -> 3;
        };
    }

    private static JsonObject asObject(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            JsonElement parsed = JsonParser.parseString(raw);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Chip position(JsonObject object) {
        if (isNumber(object.get("x")) && isNumber(object.get("y")) && isNumber(object.get("z"))) {
            return new Chip(
                    Kind.POS,
                    "",
                    number(object.get("x")) + " " + number(object.get("y")) + " " + number(object.get("z")));
        }
        return null;
    }

    private static void addValue(List<Chip> chips, String key, JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return;
        }
        if (value.isJsonArray()) {
            for (JsonElement element : value.getAsJsonArray()) {
                addValue(chips, key, element);
            }
            return;
        }
        if (value.isJsonObject()) {
            chips.add(new Chip(Kind.TEXT, key, clip(value.toString())));
            return;
        }
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        if (primitive.isNumber()) {
            String number = number(primitive);
            chips.add(COUNT_KEYS.contains(key) ? new Chip(Kind.COUNT, "", number) : new Chip(Kind.TEXT, key, number));
            return;
        }
        if (primitive.isBoolean()) {
            if (primitive.getAsBoolean()) {
                chips.add(new Chip(Kind.TEXT, "", key));
            }
            return;
        }
        String text = primitive.getAsString().trim();
        if (text.isEmpty()) {
            return;
        }
        if (isResourceKey(key) && RESOURCE_ID.matcher(text).matches()) {
            chips.add(new Chip(Kind.ITEM, "", text));
        } else {
            chips.add(new Chip(Kind.TEXT, key, clip(text)));
        }
    }

    private static boolean isResourceKey(String key) {
        return switch (key) {
            case "block",
                    "item",
                    "blocks",
                    "items",
                    "material",
                    "resource",
                    "target",
                    "tool",
                    "food",
                    "crop",
                    "seed",
                    "fuel",
                    "ingredient",
                    "result",
                    "output",
                    "weapon",
                    "armor",
                    "give",
                    "take" -> true;
            default -> false;
        };
    }

    private static boolean isNumber(JsonElement element) {
        return element != null
                && element.isJsonPrimitive()
                && element.getAsJsonPrimitive().isNumber();
    }

    private static String number(JsonElement element) {
        double value = element.getAsDouble();
        return value == Math.rint(value) ? Long.toString((long) value) : String.format(Locale.ROOT, "%.1f", value);
    }

    private static String clip(String text) {
        return text.length() <= MAX_VALUE_CHARS ? text : text.substring(0, MAX_VALUE_CHARS - 3) + "...";
    }
}
