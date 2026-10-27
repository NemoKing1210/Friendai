package dev.friendai.trading;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.TreeMap;

/** Canonical, registry-independent stack data. Count is separate from component identity. */
public record TradeItem(String itemId, String componentsJson, int count) {
    public static final int MAX_COUNT = 1_000_000;
    public static final int MAX_JSON_BYTES = 8 * 1024;
    public static final int MAX_JSON_DEPTH = 16;

    public TradeItem {
        requireId(itemId);
        requireCount(count);
        componentsJson = canonicalJson(componentsJson);
    }

    static void requireId(String id) {
        if (id == null || id.length() > 256 || !id.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) {
            throw new IllegalArgumentException("invalid namespaced id");
        }
    }

    static void requireCount(int count) {
        if (count < 1 || count > MAX_COUNT) throw new IllegalArgumentException("trade count out of range");
    }

    static String requireText(String text, int max) {
        if (text == null || text.length() > max || text.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid trade text");
        }
        return text;
    }

    /** Bounds are checked before parsing; recursion stops before allocating an over-depth subtree. */
    static String canonicalJson(String json) {
        if (json == null || json.length() > MAX_JSON_BYTES
                || json.getBytes(StandardCharsets.UTF_8).length > MAX_JSON_BYTES) {
            throw new IllegalArgumentException("trade JSON exceeds field limit");
        }
        try (var reader = new JsonReader(new StringReader(json))) {
            reader.setStrictness(Strictness.STRICT);
            JsonElement value = readJson(reader, 0);
            if (!value.isJsonObject() || reader.peek() != JsonToken.END_DOCUMENT) {
                throw new IllegalArgumentException("trade JSON must be one object");
            }
            String canonical = value.toString();
            if (canonical.getBytes(StandardCharsets.UTF_8).length > MAX_JSON_BYTES) {
                throw new IllegalArgumentException("canonical trade JSON exceeds field limit");
            }
            return canonical;
        } catch (IOException | NumberFormatException | ArithmeticException | IllegalStateException e) {
            throw new IllegalArgumentException("invalid trade JSON", e);
        }
    }

    /** UTF-8 must never replace a malformed UTF-16 value and erase a distinction used by offer identity. */
    private static String requireWellFormedUnicode(String text) {
        for (int i = 0; i < text.length(); i++) {
            char current = text.charAt(i);
            if (Character.isHighSurrogate(current)) {
                if (i + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(i + 1))) {
                    throw new IllegalArgumentException("unpaired surrogate in trade JSON");
                }
                i++;
            } else if (Character.isLowSurrogate(current)) {
                throw new IllegalArgumentException("unpaired surrogate in trade JSON");
            }
        }
        return text;
    }

    private static JsonElement readJson(JsonReader reader, int depth) throws IOException {
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                if (depth >= MAX_JSON_DEPTH) throw new IllegalArgumentException("trade JSON nesting limit");
                reader.beginObject();
                var fields = new TreeMap<String, JsonElement>();
                while (reader.hasNext()) {
                    String key = requireWellFormedUnicode(reader.nextName());
                    if (fields.containsKey(key)) throw new IllegalArgumentException("duplicate trade JSON key");
                    fields.put(key, readJson(reader, depth + 1));
                }
                reader.endObject();
                var object = new JsonObject();
                fields.forEach(object::add);
                yield object;
            }
            case BEGIN_ARRAY -> {
                if (depth >= MAX_JSON_DEPTH) throw new IllegalArgumentException("trade JSON nesting limit");
                reader.beginArray();
                var array = new JsonArray();
                while (reader.hasNext()) array.add(readJson(reader, depth + 1));
                reader.endArray();
                yield array;
            }
            case STRING -> new JsonPrimitive(requireWellFormedUnicode(reader.nextString()));
            case NUMBER -> {
                BigDecimal number = new BigDecimal(reader.nextString()).stripTrailingZeros();
                // Scientific notation keeps extreme exponents bounded; no toPlainString expansion.
                yield new JsonPrimitive(number);
            }
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NULL -> {
                reader.nextNull();
                yield JsonNull.INSTANCE;
            }
            default -> throw new IllegalArgumentException("invalid trade JSON token");
        };
    }
}
