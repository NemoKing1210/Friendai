package dev.friendai.foundation.diagnostics;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The one-line JSON form of a record. Encoding enforces every limit in {@link LogLimits} and never throws;
 * decoding rejects a line it cannot trust instead of throwing into a reader.
 */
public final class LogJson {
    public static final int SCHEMA = 1;
    private static final String ELLIPSIS = "…";
    private static final int MIN_TEXT_CHARS = 16;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private LogJson() {}

    public static String encode(LogRecord record) {
        boolean[] truncated = {false};
        JsonObject object = toJson(record, truncated);
        shrinkToFit(object, truncated);
        if (truncated[0] && !object.has("truncated")) {
            object.addProperty("truncated", true);
        }
        return GSON.toJson(object);
    }

    public static Optional<LogRecord> decode(String line) {
        if (line == null || line.isBlank()) return Optional.empty();
        try {
            JsonElement element = JsonParser.parseString(line);
            if (!element.isJsonObject()) return Optional.empty();
            JsonObject object = element.getAsJsonObject();
            if (integer(object, "schema") != SCHEMA) return Optional.empty();
            LogLevel level = LogLevel.parse(string(object, "level")).orElse(null);
            LogCategory category = LogCategory.parse(string(object, "category")).orElse(null);
            if (level == null || category == null) return Optional.empty();
            long seq = requiredLong(object, "seq");
            long timeMillis = requiredLong(object, "timeMillis");
            return Optional.of(new LogRecord(
                    seq,
                    timeMillis,
                    level,
                    category,
                    kind(string(object, "kind")),
                    string(object, "event"),
                    uuid(string(object, "owner")),
                    uuid(string(object, "companion")),
                    string(object, "session"),
                    longValue(object, "turn"),
                    longValue(object, "plan"),
                    longValue(object, "job"),
                    longValue(object, "task"),
                    string(object, "toolCall"),
                    longValue(object, "cause"),
                    longValue(object, "durationMs") == 0 && !object.has("durationMs")
                            ? -1L
                            : longValue(object, "durationMs"),
                    string(object, "msg"),
                    data(object),
                    error(object)));
        } catch (RuntimeException invalid) {
            return Optional.empty();
        }
    }

    public static String encodeHeader(LogFileHeader header) {
        JsonObject object = new JsonObject();
        object.addProperty("schema", SCHEMA);
        object.addProperty("kind", "header");
        object.addProperty("boot", header.boot());
        object.addProperty("mod", header.mod());
        object.addProperty("minecraft", header.minecraft());
        addUuid(object, "owner", header.owner());
        object.addProperty("name", header.name());
        object.addProperty("level", header.level());
        JsonArray categories = new JsonArray();
        for (String category : header.categories()) {
            categories.add(category);
        }
        object.add("categories", categories);
        object.addProperty("started", header.started());
        return GSON.toJson(object);
    }

    public static Optional<LogFileHeader> decodeHeader(String line) {
        if (line == null || line.isBlank()) return Optional.empty();
        try {
            JsonElement element = JsonParser.parseString(line);
            if (!element.isJsonObject()) return Optional.empty();
            JsonObject object = element.getAsJsonObject();
            if (integer(object, "schema") != SCHEMA || !"header".equals(string(object, "kind"))) {
                return Optional.empty();
            }
            List<String> categories = new ArrayList<>();
            if (object.has("categories") && object.get("categories").isJsonArray()) {
                for (JsonElement category : object.getAsJsonArray("categories")) {
                    categories.add(category.getAsString());
                }
            }
            return Optional.of(new LogFileHeader(
                    SCHEMA,
                    string(object, "boot"),
                    string(object, "mod"),
                    string(object, "minecraft"),
                    uuid(string(object, "owner")),
                    string(object, "name"),
                    string(object, "level"),
                    categories,
                    longValue(object, "started")));
        } catch (RuntimeException invalid) {
            return Optional.empty();
        }
    }

    private static JsonObject toJson(LogRecord record, boolean[] truncated) {
        JsonObject object = new JsonObject();
        object.addProperty("schema", SCHEMA);
        object.addProperty("seq", record.seq());
        object.addProperty("timeMillis", record.timeMillis());
        object.addProperty("level", record.level().token());
        object.addProperty("category", record.category().token());
        if (record.kind() != null) {
            object.addProperty("kind", record.kind().name());
        }
        object.addProperty("event", record.event());
        addUuid(object, "owner", record.owner());
        addUuid(object, "companion", record.companion());
        object.addProperty("session", cap(record.session(), 16, truncated));
        object.addProperty("turn", record.turn());
        object.addProperty("plan", record.plan());
        object.addProperty("job", record.job());
        object.addProperty("task", record.task());
        object.addProperty("toolCall", cap(record.toolCall(), 64, truncated));
        object.addProperty("cause", record.cause());
        object.addProperty("durationMs", record.durationMs());
        if (record.data() != null && !record.data().isEmpty()) {
            JsonObject data = new JsonObject();
            int keys = 0;
            for (Map.Entry<String, String> entry : record.data().entrySet()) {
                if (keys++ >= LogLimits.MAX_DATA_KEYS) {
                    truncated[0] = true;
                    break;
                }
                data.addProperty(
                        cap(entry.getKey(), LogLimits.MAX_KEY_CHARS, truncated),
                        cap(entry.getValue(), LogLimits.MAX_VALUE_CHARS, truncated));
            }
            object.add("data", data);
        }
        object.addProperty("msg", cap(record.message(), LogLimits.MAX_MESSAGE_CHARS, truncated));
        if (record.error() != null) {
            JsonObject error = new JsonObject();
            error.addProperty("type", cap(record.error().type(), 80, truncated));
            error.addProperty("msg", cap(record.error().message(), LogLimits.MAX_MESSAGE_CHARS, truncated));
            error.addProperty("stack", cap(record.error().stack(), LogLimits.MAX_STACK_CHARS, truncated));
            int frames = Math.min(record.error().frames(), LogLimits.MAX_STACK_FRAMES);
            if (frames < record.error().frames()) {
                truncated[0] = true;
            }
            error.addProperty("frames", frames);
            object.add("error", error);
        }
        return object;
    }

    private static void shrinkToFit(JsonObject object, boolean[] truncated) {
        int guard = 0;
        while (bytes(object) > LogLimits.MAX_LINE_BYTES && guard++ < 1024) {
            boolean changed = false;
            if (object.has("data") && object.get("data").isJsonObject()) {
                JsonObject data = object.getAsJsonObject("data");
                if (!data.isEmpty()) {
                    List<String> keys = new ArrayList<>(data.keySet());
                    data.remove(keys.get(keys.size() - 1));
                    truncated[0] = true;
                    changed = true;
                }
            }
            if (!changed && object.has("msg")) {
                changed = shrink(object, "msg", truncated);
            }
            if (!changed && object.has("error") && object.get("error").isJsonObject()) {
                JsonObject error = object.getAsJsonObject("error");
                changed = shrink(error, "stack", truncated) || shrink(error, "msg", truncated);
            }
            if (!changed) {
                truncateAll(object, truncated);
                break;
            }
        }
    }

    private static void truncateAll(JsonObject object, boolean[] truncated) {
        if (object.has("msg")) {
            object.addProperty("msg", first(object.get("msg").getAsString(), MIN_TEXT_CHARS));
        }
        if (object.has("data")) {
            object.remove("data");
            truncated[0] = true;
        }
        if (object.has("error") && object.get("error").isJsonObject()) {
            object.getAsJsonObject("error").remove("stack");
            truncated[0] = true;
        }
    }

    private static boolean shrink(JsonObject object, String field, boolean[] truncated) {
        if (!object.has(field)) return false;
        String value = object.get(field).getAsString();
        if (value.length() <= MIN_TEXT_CHARS) return false;
        object.addProperty(field, first(value, value.length() / 2) + ELLIPSIS);
        truncated[0] = true;
        return true;
    }

    private static int bytes(JsonObject object) {
        return GSON.toJson(object).getBytes(StandardCharsets.UTF_8).length;
    }

    private static String cap(String text, int max, boolean[] truncated) {
        String value = text == null ? "" : text;
        if (value.length() <= max) return value;
        truncated[0] = true;
        return max <= ELLIPSIS.length() ? ELLIPSIS : value.substring(0, max - ELLIPSIS.length()) + ELLIPSIS;
    }

    private static String first(String text, int chars) {
        return text.length() <= chars ? text : text.substring(0, chars);
    }

    private static void addUuid(JsonObject object, String name, UUID value) {
        if (value != null) {
            object.addProperty(name, value.toString());
        }
    }

    private static LogKind kind(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return LogKind.valueOf(value);
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    private static UUID uuid(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static Map<String, String> data(JsonObject object) {
        if (!object.has("data") || !object.get("data").isJsonObject()) return Map.of();
        Map<String, String> data = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry :
                object.getAsJsonObject("data").entrySet()) {
            data.put(
                    entry.getKey(),
                    entry.getValue().isJsonNull() ? "" : entry.getValue().getAsString());
        }
        return Collections.unmodifiableMap(data);
    }

    private static LogError error(JsonObject object) {
        if (!object.has("error") || !object.get("error").isJsonObject()) return null;
        JsonObject error = object.getAsJsonObject("error");
        return new LogError(
                string(error, "type"), string(error, "msg"), string(error, "stack"), integer(error, "frames"));
    }

    private static String string(JsonObject object, String name) {
        if (!object.has(name) || object.get(name).isJsonNull()) return "";
        JsonElement value = object.get(name);
        return value.isJsonPrimitive() ? value.getAsString() : "";
    }

    private static int integer(JsonObject object, String name) {
        return (int) longValue(object, name);
    }

    private static long longValue(JsonObject object, String name) {
        if (!object.has(name) || object.get(name).isJsonNull()) return 0L;
        JsonElement value = object.get(name);
        return value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber() ? value.getAsLong() : 0L;
    }

    private static long requiredLong(JsonObject object, String name) {
        if (!object.has(name)
                || !object.get(name).isJsonPrimitive()
                || !object.get(name).getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("missing " + name);
        }
        return object.get(name).getAsLong();
    }
}
