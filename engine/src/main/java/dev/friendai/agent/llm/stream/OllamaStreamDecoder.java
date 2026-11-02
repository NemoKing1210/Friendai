package dev.friendai.agent.llm.stream;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;

/**
 * Ollama NDJSON stream: the transport supplies one JSON line per {@link StreamEvent} (Ollama does not use SSE blank
 * lines). {@code message.content} is text, {@code done} ends the stream and the eval counts report usage.
 */
public final class OllamaStreamDecoder implements StreamDecoder {
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
            if (root.has("message") && root.get("message").isJsonObject()) {
                JsonObject message = root.getAsJsonObject("message");
                if (message.has("content") && message.get("content").isJsonPrimitive()) {
                    String text = message.get("content").getAsString();
                    if (!text.isEmpty()) deltas.add(new StreamDelta.Text(text));
                }
            }
            if (root.has("done")
                    && root.get("done").isJsonPrimitive()
                    && root.get("done").getAsBoolean()) {
                deltas.add(new StreamDelta.Done());
                done = true;
            }
            if (root.has("prompt_eval_count") || root.has("eval_count")) {
                deltas.add(new StreamDelta.Usage(
                        root.has("prompt_eval_count")
                                ? root.get("prompt_eval_count").getAsInt()
                                : null,
                        root.has("eval_count") ? root.get("eval_count").getAsInt() : null));
            }
            return deltas;
        } catch (RuntimeException invalid) {
            failed = true;
            return List.of();
        }
    }

    public boolean failed() {
        return failed;
    }
}
