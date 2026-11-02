package dev.friendai.agent.llm.stream;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;

/**
 * Anthropic Messages stream. Text arrives in {@code content_block_delta} events, tool arguments in
 * {@code input_json_delta} fragments, and {@code message_stop} ends the response.
 */
public final class AnthropicStreamDecoder implements StreamDecoder {
    private boolean done;
    private boolean failed;

    @Override
    public List<StreamDelta> accept(StreamEvent event) {
        if (done || failed || event == null) return List.of();
        String data = event.data().strip();
        if (data.isEmpty()) return List.of();
        try {
            JsonObject root = JsonParser.parseString(data).getAsJsonObject();
            String type = root.has("type") ? root.get("type").getAsString() : "";
            List<StreamDelta> deltas = new ArrayList<>(1);
            switch (type) {
                case "content_block_delta" -> {
                    JsonObject delta = root.has("delta") ? root.getAsJsonObject("delta") : new JsonObject();
                    String kind = delta.has("type") ? delta.get("type").getAsString() : "";
                    int index = root.has("index") ? root.get("index").getAsInt() : 0;
                    if ("text_delta".equals(kind) && delta.has("text")) {
                        deltas.add(new StreamDelta.Text(delta.get("text").getAsString()));
                    } else if ("input_json_delta".equals(kind) && delta.has("partial_json")) {
                        deltas.add(new StreamDelta.ToolCall(
                                index, "", "", delta.get("partial_json").getAsString()));
                    }
                }
                case "content_block_start" -> {
                    JsonObject block =
                            root.has("content_block") ? root.getAsJsonObject("content_block") : new JsonObject();
                    if ("tool_use".equals(block.has("type") ? block.get("type").getAsString() : "")) {
                        deltas.add(new StreamDelta.ToolCall(
                                root.has("index") ? root.get("index").getAsInt() : 0,
                                block.has("id") ? block.get("id").getAsString() : "",
                                block.has("name") ? block.get("name").getAsString() : "",
                                ""));
                    }
                }
                case "message_delta" -> {
                    JsonObject usage = root.has("usage") ? root.getAsJsonObject("usage") : new JsonObject();
                    if (usage.has("output_tokens")) {
                        deltas.add(new StreamDelta.Usage(
                                null, usage.get("output_tokens").getAsInt()));
                    }
                }
                case "message_stop" -> {
                    deltas.add(new StreamDelta.Done());
                    done = true;
                }
                case "error" -> {
                    failed = true;
                }
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
