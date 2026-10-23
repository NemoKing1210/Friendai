package dev.friendai.llm.protocol;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.friendai.llm.provider.Endpoint;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;

/** Helpers shared by all protocol implementations. */
public final class ProtocolSupport {
    private ProtocolSupport() {}

    /** Parses tool arguments sent as a JSON string. Returns null if blank, malformed or not an object. */
    public static JsonObject parseArguments(String raw) {
        if (raw == null || raw.isBlank()) {
            return new JsonObject();
        }
        try {
            JsonElement e = JsonParser.parseString(raw);
            return e.isJsonObject() ? e.getAsJsonObject() : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    public static String callId(int index) {
        return "call_" + index;
    }

    public static Map<String, String> headers(Endpoint endpoint) {
        Map<String, String> h = new LinkedHashMap<>();
        endpoint.authStyle().apply(h, endpoint.apiKey());
        h.putAll(endpoint.extraHeaders());
        return h;
    }

    public static URI uri(Endpoint endpoint, String path) {
        return URI.create(endpoint.baseUrl() + path);
    }

    /** Serializes tool arguments for the wire as a JSON object string; "{}" when they were malformed. */
    public static String argumentsString(JsonObject arguments) {
        return arguments == null ? "{}" : arguments.toString();
    }

    public static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    public static JsonObject obj(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    public static JsonArray arr(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : new JsonArray();
    }

    public static int intOr(JsonObject o, String key, int fallback) {
        JsonElement e = o == null ? null : o.get(key);
        return e == null || e.isJsonNull() ? fallback : e.getAsInt();
    }

    public static List<String> idsOf(JsonArray array, String field) {
        List<String> out = new ArrayList<>();
        for (JsonElement e : array) {
            String v = str(e.getAsJsonObject(), field);
            if (v != null) {
                out.add(v);
            }
        }
        return out;
    }
}
