package dev.friendai.agent.llm.stream;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;

/**
 * Gemini {@code streamGenerateContent} stream: each event is a JSON object with {@code candidates[0].content.parts}.
 * Text parts become text increments; a {@code functionCall} part is complete and becomes one tool-call increment;
 * {@code finishReason} ends the stream and {@code usageMetadata} reports usage.
 */
public final class GeminiStreamDecoder implements StreamDecoder {
    private boolean done;
    private boolean failed;

    @Override
    public List<StreamDelta> accept(StreamEvent event) {
        if (done || failed || event == null) return List.of();
        String data = event.data().strip();
        if (data.isEmpty()) return List.of();
        try {
            JsonObject root = JsonParser.parseString(data).getAsJsonObject();
            List<StreamDelta> deltas = new ArrayList<>(1);
            JsonObject candidate = firstCandidate(root);
            if (candidate != null) {
                JsonObject content = candidate.has("content") ? candidate.getAsJsonObject("content") : new JsonObject();
                if (content.has("parts") && content.get("parts").isJsonArray()) {
                    JsonArray parts = content.getAsJsonArray("parts");
                    for (int i = 0; i < parts.size(); i++) {
                        JsonElement element = parts.get(i);
                        if (!element.isJsonObject()) continue;
                        JsonObject part = element.getAsJsonObject();
                        if (part.has("text") && part.get("text").isJsonPrimitive()) {
                            String text = part.get("text").getAsString();
                            if (!text.isEmpty()) deltas.add(new StreamDelta.Text(text));
                        } else if (part.has("functionCall")
                                && part.get("functionCall").isJsonObject()) {
                            JsonObject call = part.getAsJsonObject("functionCall");
                            deltas.add(new StreamDelta.ToolCall(
                                    i,
                                    "",
                                    call.has("name") ? call.get("name").getAsString() : "",
                                    call.has("args") ? call.get("args").toString() : "{}"));
                        }
                    }
                }
                if (candidate.has("finishReason")
                        && !candidate.get("finishReason").isJsonNull()) {
                    deltas.add(new StreamDelta.Done());
                    done = true;
                }
            }
            if (root.has("usageMetadata") && root.get("usageMetadata").isJsonObject()) {
                JsonObject usage = root.getAsJsonObject("usageMetadata");
                deltas.add(new StreamDelta.Usage(
                        usage.has("promptTokenCount")
                                ? usage.get("promptTokenCount").getAsInt()
                                : null,
                        usage.has("candidatesTokenCount")
                                ? usage.get("candidatesTokenCount").getAsInt()
                                : null));
            }
            return deltas;
        } catch (RuntimeException invalid) {
            failed = true;
            return List.of();
        }
    }

    private static JsonObject firstCandidate(JsonObject root) {
        if (!root.has("candidates") || !root.get("candidates").isJsonArray()) return null;
        JsonArray candidates = root.getAsJsonArray("candidates");
        return candidates.isEmpty() || !candidates.get(0).isJsonObject()
                ? null
                : candidates.get(0).getAsJsonObject();
    }

    public boolean failed() {
        return failed;
    }
}
