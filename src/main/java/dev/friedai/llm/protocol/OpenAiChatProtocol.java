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
import java.util.concurrent.CompletableFuture;

/** OpenAI Chat Completions wire format (also spoken by most compatible gateways). */
public final class OpenAiChatProtocol implements LlmProtocol {
    private final HttpTransport transport;

    public OpenAiChatProtocol(HttpTransport transport) {
        this.transport = transport;
    }

    @Override
    public String id() {
        return "openai_chat";
    }

    @Override
    public CompletableFuture<LlmResponse> complete(Endpoint endpoint, LlmRequest request) {
        return transport
                .postJson(ProtocolSupport.uri(endpoint, "/chat/completions"), ProtocolSupport.headers(endpoint), toWire(request))
                .thenApply(this::fromWire);
    }

    @Override
    public CompletableFuture<List<String>> listModels(Endpoint endpoint) {
        return transport
                .getJson(ProtocolSupport.uri(endpoint, "/models"), ProtocolSupport.headers(endpoint))
                .thenApply(o -> ProtocolSupport.idsOf(ProtocolSupport.arr(o, "data"), "id"));
    }

    public JsonObject toWire(LlmRequest r) {
        JsonObject body = new JsonObject();
        body.addProperty("model", r.model());
        JsonArray messages = new JsonArray();
        JsonObject system = new JsonObject();
        system.addProperty("role", "system");
        system.addProperty("content", r.system());
        messages.add(system);
        for (Message m : r.messages()) {
            messages.add(messageToWire(m));
        }
        body.add("messages", messages);
        if (r.tools() != null && !r.tools().isEmpty()) {
            JsonArray tools = new JsonArray();
            for (ToolSpec t : r.tools()) {
                JsonObject fn = new JsonObject();
                fn.addProperty("name", t.name());
                fn.addProperty("description", t.description());
                fn.add("parameters", t.parametersSchema());
                JsonObject tool = new JsonObject();
                tool.addProperty("type", "function");
                tool.add("function", fn);
                tools.add(tool);
            }
            body.add("tools", tools);
        }
        body.addProperty("max_tokens", r.maxTokens());
        if (r.temperature() != null) {
            body.addProperty("temperature", r.temperature());
        }
        return body;
    }

    private static JsonObject messageToWire(Message m) {
        JsonObject o = new JsonObject();
        switch (m.role()) {
            case USER -> {
                o.addProperty("role", "user");
                o.addProperty("content", m.text());
            }
            case TOOL -> {
                o.addProperty("role", "tool");
                o.addProperty("tool_call_id", m.toolCallId());
                o.addProperty("content", m.text());
            }
            case ASSISTANT -> {
                o.addProperty("role", "assistant");
                if (m.text().isEmpty() && !m.toolCalls().isEmpty()) {
                    o.add("content", com.google.gson.JsonNull.INSTANCE);
                } else {
                    o.addProperty("content", m.text());
                }
                if (!m.toolCalls().isEmpty()) {
                    JsonArray calls = new JsonArray();
                    for (ToolCall c : m.toolCalls()) {
                        JsonObject fn = new JsonObject();
                        fn.addProperty("name", c.name());
                        fn.addProperty("arguments", ProtocolSupport.argumentsString(c.arguments()));
                        JsonObject call = new JsonObject();
                        call.addProperty("id", c.id());
                        call.addProperty("type", "function");
                        call.add("function", fn);
                        calls.add(call);
                    }
                    o.add("tool_calls", calls);
                }
            }
        }
        return o;
    }

    public LlmResponse fromWire(JsonObject o) {
        JsonArray choices = ProtocolSupport.arr(o, "choices");
        JsonObject choice = choices.isEmpty() ? new JsonObject() : choices.get(0).getAsJsonObject();
        JsonObject message = ProtocolSupport.obj(choice, "message");
        String text = "";
        List<ToolCall> calls = new ArrayList<>();
        if (message != null) {
            String content = ProtocolSupport.str(message, "content");
            text = content == null ? "" : content;
            int index = 0;
            for (JsonElement e : ProtocolSupport.arr(message, "tool_calls")) {
                JsonObject call = e.getAsJsonObject();
                JsonObject fn = ProtocolSupport.obj(call, "function");
                String raw = fn == null ? null : ProtocolSupport.str(fn, "arguments");
                String id = ProtocolSupport.str(call, "id");
                calls.add(new ToolCall(
                        id != null ? id : ProtocolSupport.callId(index),
                        fn == null ? "" : ProtocolSupport.str(fn, "name"),
                        ProtocolSupport.parseArguments(raw),
                        raw,
                        null));
                index++;
            }
        }
        String finish = ProtocolSupport.str(choice, "finish_reason");
        StopReason stop = switch (finish == null ? "" : finish) {
            case "stop" -> StopReason.END;
            case "tool_calls" -> StopReason.TOOL_USE;
            case "length" -> StopReason.MAX_TOKENS;
            default -> StopReason.OTHER;
        };
        JsonObject usage = ProtocolSupport.obj(o, "usage");
        return new LlmResponse(
                text,
                calls,
                stop,
                new Usage(ProtocolSupport.intOr(usage, "prompt_tokens", 0), ProtocolSupport.intOr(usage, "completion_tokens", 0)));
    }
}
