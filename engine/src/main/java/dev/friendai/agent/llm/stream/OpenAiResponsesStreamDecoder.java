package dev.friendai.agent.llm.stream;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;

/**
 * OpenAI Responses API stream. Text arrives as {@code response.output_text.delta}; a function call is announced by
 * {@code response.output_item.added} and its arguments follow as
 * {@code response.function_call_arguments.delta}; {@code response.completed} ends the response and carries usage.
 */
public final class OpenAiResponsesStreamDecoder implements StreamDecoder {
    private boolean done;
    private boolean failed;

    @Override
    public List<StreamDelta> accept(StreamEvent event) {
        if (done || failed || event == null) return List.of();
        String data = event.data().strip();
        if (data.isEmpty() || "[DONE]".equals(data)) {
            if ("[DONE]".equals(data)) {
                done = true;
                return List.of(new StreamDelta.Done());
            }
            return List.of();
        }
        try {
            JsonObject root = JsonParser.parseString(data).getAsJsonObject();
            String type = root.has("type") ? root.get("type").getAsString() : "";
            List<StreamDelta> deltas = new ArrayList<>(1);
            switch (type) {
                case "response.output_text.delta" -> {
                    if (root.has("delta")) {
                        String text = root.get("delta").getAsString();
                        if (!text.isEmpty()) deltas.add(new StreamDelta.Text(text));
                    }
                }
                case "response.output_item.added" -> {
                    JsonObject item = root.has("item") ? root.getAsJsonObject("item") : new JsonObject();
                    if ("function_call"
                            .equals(item.has("type") ? item.get("type").getAsString() : "")) {
                        deltas.add(new StreamDelta.ToolCall(
                                root.has("output_index")
                                        ? root.get("output_index").getAsInt()
                                        : 0,
                                item.has("call_id") ? item.get("call_id").getAsString() : "",
                                item.has("name") ? item.get("name").getAsString() : "",
                                ""));
                    }
                }
                case "response.function_call_arguments.delta" -> {
                    if (root.has("delta")) {
                        deltas.add(new StreamDelta.ToolCall(
                                root.has("output_index")
                                        ? root.get("output_index").getAsInt()
                                        : 0,
                                "",
                                "",
                                root.get("delta").getAsString()));
                    }
                }
                case "response.completed" -> {
                    deltas.add(new StreamDelta.Done());
                    done = true;
                    if (root.has("response") && root.get("response").isJsonObject()) {
                        JsonObject response = root.getAsJsonObject("response");
                        JsonObject usage = response.has("usage") ? response.getAsJsonObject("usage") : new JsonObject();
                        if (usage.has("input_tokens") || usage.has("output_tokens")) {
                            deltas.add(new StreamDelta.Usage(
                                    usage.has("input_tokens")
                                            ? usage.get("input_tokens").getAsInt()
                                            : null,
                                    usage.has("output_tokens")
                                            ? usage.get("output_tokens").getAsInt()
                                            : null));
                        }
                    }
                }
                case "error" -> failed = true;
                default -> {}
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
