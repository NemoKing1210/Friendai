package dev.friedai.llm.protocol;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.friedai.llm.http.HttpTransport;
import dev.friedai.llm.model.LlmRequest;
import dev.friedai.llm.model.LlmResponse;
import dev.friedai.llm.model.Message;
import dev.friedai.llm.model.StopReason;
import dev.friedai.llm.model.ToolCall;
import dev.friedai.llm.model.ToolSpec;
import dev.friedai.llm.model.Usage;
import dev.friedai.llm.provider.Endpoint;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Anthropic Messages API wire format. */
public final class AnthropicMessagesProtocol implements LlmProtocol {
    private final HttpTransport transport;

    public AnthropicMessagesProtocol(HttpTransport transport) {
        this.transport = transport;
    }

    @Override
    public String id() {
        return "anthropic_messages";
    }

    private static Map<String, String> headers(Endpoint endpoint) {
        Map<String, String> h = ProtocolSupport.headers(endpoint);
        h.put("anthropic-version", "2023-06-01");
        return h;
    }

    @Override
    public CompletableFuture<LlmResponse> complete(Endpoint endpoint, LlmRequest request) {
        return transport
                .postJson(ProtocolSupport.uri(endpoint, "/messages"), headers(endpoint), toWire(request))
                .thenApply(this::fromWire);
    }

    @Override
    public CompletableFuture<List<String>> listModels(Endpoint endpoint) {
        return transport
                .getJson(ProtocolSupport.uri(endpoint, "/models"), headers(endpoint))
                .thenApply(o -> ProtocolSupport.idsOf(ProtocolSupport.arr(o, "data"), "id"));
    }

    public JsonObject toWire(LlmRequest r) {
        JsonObject body = new JsonObject();
        body.addProperty("model", r.model());
        body.addProperty("system", r.system());
        body.addProperty("max_tokens", r.maxTokens());
        if (r.temperature() != null) {
            body.addProperty("temperature", r.temperature());
        }
        if (r.tools() != null && !r.tools().isEmpty()) {
            JsonArray tools = new JsonArray();
            for (ToolSpec t : r.tools()) {
                JsonObject tool = new JsonObject();
                tool.addProperty("name", t.name());
                tool.addProperty("description", t.description());
                tool.add("input_schema", t.parametersSchema());
                tools.add(tool);
            }
            body.add("tools", tools);
        }
        JsonArray messages = new JsonArray();
        JsonObject pendingUser = null;
        for (Message m : r.messages()) {
            switch (m.role()) {
                case USER, TOOL -> {
                    if (pendingUser == null) {
                        pendingUser = new JsonObject();
                        pendingUser.addProperty("role", "user");
                        pendingUser.add("content", new JsonArray());
                    }
                    JsonObject block = new JsonObject();
                    if (m.role() == dev.friedai.llm.model.Role.TOOL) {
                        block.addProperty("type", "tool_result");
                        block.addProperty("tool_use_id", m.toolCallId());
                        block.addProperty("content", m.text());
                    } else {
                        block.addProperty("type", "text");
                        block.addProperty("text", m.text());
                    }
                    pendingUser.getAsJsonArray("content").add(block);
                }
                case ASSISTANT -> {
                    if (pendingUser != null) {
                        messages.add(pendingUser);
                        pendingUser = null;
                    }
                    JsonObject a = new JsonObject();
                    a.addProperty("role", "assistant");
                    JsonArray content = new JsonArray();
                    if (!m.text().isEmpty()) {
                        JsonObject text = new JsonObject();
                        text.addProperty("type", "text");
                        text.addProperty("text", m.text());
                        content.add(text);
                    }
                    for (ToolCall c : m.toolCalls()) {
                        JsonObject use = new JsonObject();
                        use.addProperty("type", "tool_use");
                        use.addProperty("id", c.id());
                        use.addProperty("name", c.name());
                        use.add("input", c.arguments() == null ? new JsonObject() : c.arguments());
                        content.add(use);
                    }
                    a.add("content", content);
                    messages.add(a);
                }
            }
        }
        if (pendingUser != null) {
            messages.add(pendingUser);
        }
        body.add("messages", messages);
        return body;
    }

    public LlmResponse fromWire(JsonObject o) {
        StringBuilder text = new StringBuilder();
        List<ToolCall> calls = new ArrayList<>();
        for (JsonElement e : ProtocolSupport.arr(o, "content")) {
            JsonObject block = e.getAsJsonObject();
            String type = ProtocolSupport.str(block, "type");
            if ("text".equals(type)) {
                text.append(ProtocolSupport.str(block, "text"));
            } else if ("tool_use".equals(type)) {
                JsonObject input = ProtocolSupport.obj(block, "input");
                calls.add(new ToolCall(
                        ProtocolSupport.str(block, "id"),
                        ProtocolSupport.str(block, "name"),
                        input == null ? new JsonObject() : input,
                        input == null ? "{}" : input.toString(),
                        null));
            }
        }
        String stopReason = ProtocolSupport.str(o, "stop_reason");
        StopReason stop = switch (stopReason == null ? "" : stopReason) {
            case "tool_use" -> StopReason.TOOL_USE;
            case "end_turn", "stop_sequence" -> StopReason.END;
            case "max_tokens" -> StopReason.MAX_TOKENS;
            default -> StopReason.OTHER;
        };
        JsonObject usage = ProtocolSupport.obj(o, "usage");
        return new LlmResponse(
                text.toString(),
                calls,
                stop,
                new Usage(ProtocolSupport.intOr(usage, "input_tokens", 0), ProtocolSupport.intOr(usage, "output_tokens", 0)));
    }
}
