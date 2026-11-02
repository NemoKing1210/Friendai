package dev.friendai.agent.llm.stream;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;

/**
 * OpenAI Chat Completions stream ({@code data: {...}} lines, terminated by {@code data: [DONE]}). Text deltas and
 * tool-call fragments are separate; the caller assembles tool arguments and never executes one early.
 */
public final class OpenAiChatStreamDecoder implements StreamDecoder {
    private boolean done;
    private boolean failed;

    @Override
    public List<StreamDelta> accept(StreamEvent event) {
        if (done || failed || event == null) return List.of();
        String data = event.data().strip();
        if (data.isEmpty()) return List.of();
        if ("[DONE]".equals(data)) {
            done = true;
            return List.of(new StreamDelta.Done());
        }
        try {
            JsonObject root = JsonParser.parseString(data).getAsJsonObject();
            List<StreamDelta> deltas = new ArrayList<>(2);
            JsonElement choicesElement = root.get("choices");
            if (choicesElement != null
                    && choicesElement.isJsonArray()
                    && !choicesElement.getAsJsonArray().isEmpty()) {
                JsonObject choice = choicesElement.getAsJsonArray().get(0).getAsJsonObject();
                JsonObject delta = choice.has("delta") ? choice.getAsJsonObject("delta") : new JsonObject();
                if (delta.has("content") && delta.get("content").isJsonPrimitive()) {
                    String text = delta.get("content").getAsString();
                    if (!text.isEmpty()) deltas.add(new StreamDelta.Text(text));
                }
                if (delta.has("tool_calls") && delta.get("tool_calls").isJsonArray()) {
                    for (JsonElement callElement : delta.getAsJsonArray("tool_calls")) {
                        JsonObject call = callElement.getAsJsonObject();
                        JsonObject function =
                                call.has("function") ? call.getAsJsonObject("function") : new JsonObject();
                        deltas.add(new StreamDelta.ToolCall(
                                call.has("index") ? call.get("index").getAsInt() : 0,
                                call.has("id") ? call.get("id").getAsString() : "",
                                function.has("name") ? function.get("name").getAsString() : "",
                                function.has("arguments")
                                        ? function.get("arguments").getAsString()
                                        : ""));
                    }
                }
                if (choice.has("finish_reason") && !choice.get("finish_reason").isJsonNull()) {
                    deltas.add(new StreamDelta.Done());
                    done = true;
                }
            }
            if (root.has("usage") && root.get("usage").isJsonObject()) {
                JsonObject usage = root.getAsJsonObject("usage");
                deltas.add(new StreamDelta.Usage(
                        usage.has("prompt_tokens") ? usage.get("prompt_tokens").getAsInt() : null,
                        usage.has("completion_tokens")
                                ? usage.get("completion_tokens").getAsInt()
                                : null));
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
