package dev.friendai.agent.llm;

import com.google.gson.JsonObject;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Port for provider protocol JSON requests; platform adapters own retries, status mapping and redaction. */
public interface JsonHttpTransport {
    CompletableFuture<JsonObject> postJson(URI uri, Map<String, String> headers, JsonObject body);

    CompletableFuture<JsonObject> getJson(URI uri, Map<String, String> headers);

    /** The same request with per-attempt telemetry; implementations that do not measure keep the old path. */
    default CompletableFuture<JsonObject> postJson(
            URI uri, Map<String, String> headers, JsonObject body, RequestTelemetry telemetry) {
        return postJson(uri, headers, body);
    }

    default CompletableFuture<JsonObject> getJson(URI uri, Map<String, String> headers, RequestTelemetry telemetry) {
        return getJson(uri, headers);
    }
}
