package dev.friendai.foundation.diagnostics;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Masks the values a diagnostic bundle must never carry: keys, tokens and credential-bearing URLs. */
public final class DiagnosticRedaction {
    private static final Set<String> SECRET_FIELDS =
            Set.of("apikey", "key", "token", "secret", "password", "authorization");

    private DiagnosticRedaction() {}

    /** Returns a deep copy with secret fields replaced by {@code set}/{@code not set} and URLs stripped. */
    public static JsonElement maskConfig(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return element;
        }
        if (element.isJsonArray()) {
            JsonArray masked = new JsonArray();
            for (JsonElement item : element.getAsJsonArray()) {
                masked.add(maskConfig(item));
            }
            return masked;
        }
        if (!element.isJsonObject()) {
            return element;
        }
        JsonObject masked = new JsonObject();
        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            String key = entry.getKey();
            String lower = key.toLowerCase(Locale.ROOT);
            if (SECRET_FIELDS.contains(lower)) {
                masked.addProperty(key, present(entry.getValue()));
            } else if (lower.equals("baseurl")) {
                masked.addProperty(key, safeUrl(entry.getValue()));
            } else {
                masked.add(key, maskConfig(entry.getValue()));
            }
        }
        return masked;
    }

    private static String present(JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return "not set";
        }
        if (!value.isJsonPrimitive()) {
            return "set";
        }
        return value.getAsString().isBlank() ? "not set" : "set";
    }

    private static String safeUrl(JsonElement value) {
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
            return "not set";
        }
        String text = value.getAsString();
        if (text.isBlank()) {
            return "not set";
        }
        try {
            URI uri = URI.create(text);
            if (uri.getHost() == null) {
                return "set";
            }
            return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), uri.getPath(), null, null).toString();
        } catch (Exception invalid) {
            return "set";
        }
    }
}
